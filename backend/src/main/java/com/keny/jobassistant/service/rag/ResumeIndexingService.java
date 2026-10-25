package com.keny.jobassistant.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.keny.jobassistant.exception.MatchAnalysisException;
import com.keny.jobassistant.repository.ResumeChunkRepository;
import com.keny.jobassistant.repository.ResumeChunkRepository.ChunkRow;
import com.keny.jobassistant.repository.ResumeVersionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把简历版本切块、embedding 并写入 resume_chunk。
 *
 * embedding 缓存：块内容的 SHA-256 + 模型相同的向量直接复用。用户上传新版本时通常只改了几段，
 * 只有改动过的块需要重新调用 embedding。
 * 耗时的 embedding 调用不放在数据库事务里：先读、再算、最后一次性写入。
 */
@Slf4j
@Service
public class ResumeIndexingService {

    public record IndexResult(int chunks, int embedded, int reused, boolean alreadyIndexed) {
    }

    private record VersionContent(JsonNode content, Long userId) {
    }

    private final ResumeChunkRepository chunkRepository;
    private final ResumeVersionRepository resumeVersionRepository;
    private final ResumeChunker chunker;
    private final EmbeddingClient embeddingClient;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate readTransaction;

    public ResumeIndexingService(ResumeChunkRepository chunkRepository,
                                 ResumeVersionRepository resumeVersionRepository,
                                 ResumeChunker chunker,
                                 EmbeddingClient embeddingClient,
                                 MeterRegistry meterRegistry,
                                 PlatformTransactionManager transactionManager) {
        this.chunkRepository = chunkRepository;
        this.resumeVersionRepository = resumeVersionRepository;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.meterRegistry = meterRegistry;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    public String embeddingModel() {
        return embeddingClient.model();
    }

    /**
     * 幂等：该版本在当前模型下已有块就直接返回。
     */
    public IndexResult ensureIndexed(Long resumeVersionId) {
        String model = embeddingClient.model();
        if (chunkRepository.existsForVersion(resumeVersionId, model)) {
            return new IndexResult(0, 0, 0, true);
        }
        VersionContent version = readTransaction.execute(status -> resumeVersionRepository.findById(resumeVersionId)
                .map(found -> new VersionContent(found.getContentJson(), found.getResume().getUser().getId()))
                .orElse(null));
        if (version == null || version.content() == null) {
            throw new MatchAnalysisException("MATCH_INPUT_MISSING", "Resume version has no parsed content", false);
        }

        List<ResumeChunk> chunks = chunker.chunk(version.content());
        if (chunks.isEmpty()) {
            return new IndexResult(0, 0, 0, false);
        }
        List<String> hashes = chunks.stream().map(chunk -> sha256(chunk.content())).toList();
        Map<String, float[]> cached = chunkRepository.findEmbeddingsByHash(new LinkedHashSet<>(hashes), model);

        // 同一版本里内容完全相同的块只 embedding 一次。
        Set<String> missingHashes = new LinkedHashSet<>();
        List<String> missingTexts = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (!cached.containsKey(hashes.get(i)) && missingHashes.add(hashes.get(i))) {
                missingTexts.add(chunks.get(i).content());
            }
        }
        List<float[]> computed = missingTexts.isEmpty() ? List.of() : embeddingClient.embed(missingTexts);
        int index = 0;
        for (String hash : missingHashes) {
            cached.put(hash, computed.get(index++));
        }

        List<ChunkRow> rows = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            ResumeChunk chunk = chunks.get(i);
            rows.add(new ChunkRow(chunk.key(), chunk.section(), chunk.ordinal(), chunk.content(), hashes.get(i), cached.get(hashes.get(i))));
        }
        chunkRepository.insertChunks(resumeVersionId, version.userId(), model, rows);

        int embedded = missingTexts.size();
        int reused = chunks.size() - embedded;
        meterRegistry.counter("rag.embedding.cache", "result", "hit").increment(reused);
        meterRegistry.counter("rag.embedding.cache", "result", "miss").increment(embedded);
        log.info("简历版本已索引，resumeVersionId={}, model={}, chunks={}, embedded={}, reused={}",
                resumeVersionId, model, chunks.size(), embedded, reused);
        return new IndexResult(chunks.size(), embedded, reused, false);
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
