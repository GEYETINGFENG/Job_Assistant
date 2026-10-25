package com.keny.jobassistant.repository;

import com.keny.jobassistant.service.rag.ChunkSection;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * resume_chunk 的读写。向量以 pgvector 文本格式 [0.1,0.2,...] 传输，SQL 中显式转换为 vector。
 */
@Repository
public class ResumeChunkRepository {

    public record ChunkRow(String chunkKey, ChunkSection section, int ordinal, String content, String contentHash, float[] embedding) {
    }

    public record SearchHit(String chunkKey, ChunkSection section, String content, double similarity) {
    }

    private static final String INSERT_SQL = """
            INSERT INTO resume_chunk (resume_version_id, user_id, section, chunk_key, ordinal, content,
                                      content_hash, embedding_model, embedding)
            VALUES (:versionId, :userId, :section, :chunkKey, :ordinal, :content, :contentHash, :model, CAST(:embedding AS vector))
            ON CONFLICT (resume_version_id, embedding_model, chunk_key) DO NOTHING
            """;

    /**
     * <=> 是 pgvector 的余弦距离（1 - 余弦相似度）。先按 (resume_version_id, embedding_model) 定位到该版本的块，
     * 再对这些块精确排序。
     */
    private static final String SEARCH_SQL = """
            SELECT chunk_key, section, content, 1 - (embedding <=> CAST(:query AS vector)) AS similarity
            FROM resume_chunk
            WHERE resume_version_id = :versionId AND embedding_model = :model
            ORDER BY embedding <=> CAST(:query AS vector)
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ResumeChunkRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean existsForVersion(Long versionId, String model) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM resume_chunk WHERE resume_version_id = :versionId AND embedding_model = :model)",
                new MapSqlParameterSource().addValue("versionId", versionId).addValue("model", model), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * embedding 缓存：返回这些内容哈希在同一模型下已有的向量。
     */
    public Map<String, float[]> findEmbeddingsByHash(Set<String> contentHashes, String model) {
        Map<String, float[]> result = new HashMap<>();
        if (contentHashes.isEmpty()) {
            return result;
        }
        jdbcTemplate.query("""
                        SELECT DISTINCT ON (content_hash) content_hash, embedding::text AS embedding
                        FROM resume_chunk
                        WHERE content_hash IN (:hashes) AND embedding_model = :model
                        """,
                new MapSqlParameterSource().addValue("hashes", contentHashes).addValue("model", model),
                resultSet -> {
                    result.put(resultSet.getString("content_hash"), parseVector(resultSet.getString("embedding")));
                });
        return result;
    }

    /**
     * @return 实际插入的行数；并发索引时冲突的行被忽略
     */
    public int insertChunks(Long versionId, Long userId, String model, List<ChunkRow> chunks) {
        MapSqlParameterSource[] batch = chunks.stream()
                .map(chunk -> new MapSqlParameterSource()
                        .addValue("versionId", versionId)
                        .addValue("userId", userId)
                        .addValue("section", chunk.section().name())
                        .addValue("chunkKey", chunk.chunkKey())
                        .addValue("ordinal", chunk.ordinal())
                        .addValue("content", chunk.content())
                        .addValue("contentHash", chunk.contentHash())
                        .addValue("model", model)
                        .addValue("embedding", formatVector(chunk.embedding())))
                .toArray(MapSqlParameterSource[]::new);
        int inserted = 0;
        for (int count : jdbcTemplate.batchUpdate(INSERT_SQL, batch)) {
            inserted += Math.max(count, 0);
        }
        return inserted;
    }

    public List<SearchHit> search(Long versionId, String model, float[] query, int limit) {
        return jdbcTemplate.query(SEARCH_SQL,
                new MapSqlParameterSource()
                        .addValue("versionId", versionId)
                        .addValue("model", model)
                        .addValue("query", formatVector(query))
                        .addValue("limit", limit),
                (resultSet, rowNumber) -> new SearchHit(
                        resultSet.getString("chunk_key"),
                        ChunkSection.valueOf(resultSet.getString("section")),
                        resultSet.getString("content"),
                        resultSet.getDouble("similarity")));
    }

    static String formatVector(float[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }

    static float[] parseVector(String text) {
        String body = text.substring(1, text.length() - 1);
        if (body.isEmpty()) {
            return new float[0];
        }
        String[] parts = body.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }
}
