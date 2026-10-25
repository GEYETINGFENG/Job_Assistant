package com.keny.jobassistant;

import com.keny.jobassistant.model.ai.RequirementEvidence;
import com.keny.jobassistant.repository.ResumeChunkRepository;
import com.keny.jobassistant.repository.ResumeVersionRepository;
import com.keny.jobassistant.service.rag.EmbeddingClient;
import com.keny.jobassistant.service.rag.HashingEmbeddingClient;
import com.keny.jobassistant.service.rag.RequirementExtractor;
import com.keny.jobassistant.service.rag.ResumeChunker;
import com.keny.jobassistant.service.rag.ResumeIndexingService;
import com.keny.jobassistant.service.rag.ResumeRetriever;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 存储与检索集成测试（pgvector 容器 + 完整 Flyway 迁移）。
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.datasource.hikari.maximum-pool-size=6"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ResumeIndexingIntegrationTest.EmbeddingTestConfig.class, ResumeChunkRepository.class, ResumeChunker.class,
        RequirementExtractor.class, ResumeIndexingService.class, ResumeRetriever.class})
@Tag("integration")
class ResumeIndexingIntegrationTest {

    private static final String RESUME = """
            {"summary": "Backend engineer focused on payments.",
             "skills": ["Java", "Kafka", "PostgreSQL"],
             "experience": [
               {"company": "Paystream", "position": "Senior Engineer", "description": "Built Kafka event pipelines and idempotent payment APIs."},
               {"company": "Shoply", "position": "Engineer", "description": "Tuned slow PostgreSQL queries with composite indexes."}],
             "projects": [{"name": "Throttle", "technologies": ["Redis"], "description": "Token bucket rate limiter with Redis Lua scripts."}],
             "education": [{"school": "UIUC", "degree": "B.S.", "major": "Computer Science"}]}
            """;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(TestDatabaseImages.POSTGRES_WITH_PGVECTOR)
            .withDatabaseName("jobassistant_rag_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    /** 统计实际被 embedding 的文本数量，用来验证缓存命中。 */
    static class CountingEmbeddingClient implements EmbeddingClient {
        private final EmbeddingClient delegate;
        private final String model;
        final AtomicInteger embeddedTexts = new AtomicInteger();

        CountingEmbeddingClient(EmbeddingClient delegate, String model) {
            this.delegate = delegate;
            this.model = model;
        }

        public String model() {
            return model;
        }

        public int dimensions() {
            return delegate.dimensions();
        }

        public List<float[]> embed(List<String> texts) {
            embeddedTexts.addAndGet(texts.size());
            return delegate.embed(texts);
        }
    }

    @TestConfiguration
    static class EmbeddingTestConfig {
        @Bean
        CountingEmbeddingClient embeddingClient() {
            return new CountingEmbeddingClient(new HashingEmbeddingClient(), HashingEmbeddingClient.MODEL);
        }

        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private ResumeIndexingService indexingService;
    @Autowired
    private ResumeRetriever retriever;
    @Autowired
    private ResumeChunkRepository chunkRepository;
    @Autowired
    private ResumeVersionRepository resumeVersionRepository;
    @Autowired
    private ResumeChunker chunker;
    @Autowired
    private CountingEmbeddingClient embeddingClient;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long resumeId;

    @BeforeEach
    void setUpData() {
        jdbcTemplate.execute("TRUNCATE resume_chunk, match_record, application_event, application, resume_version, resume, job, users RESTART IDENTITY CASCADE");
        jdbcTemplate.update("INSERT INTO users (id, user_account, user_password) VALUES (1, 'alice', 'hash')");
        resumeId = jdbcTemplate.queryForObject(
                "INSERT INTO resume (user_id, resume_name, latest_version_number) VALUES (1, 'Backend', 1) RETURNING id", Long.class);
        embeddingClient.embeddedTexts.set(0);
    }

    private Long insertVersion(int number, String content) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO resume_version (resume_id, version_number, content_json) VALUES (?, ?, ?::jsonb) RETURNING id",
                Long.class, resumeId, number, content);
    }

    private int chunkCount(Long versionId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM resume_chunk WHERE resume_version_id = ?", Integer.class, versionId);
    }

    @Test
    void indexingShouldStoreChunksAndBeIdempotent() {
        Long versionId = insertVersion(1, RESUME);

        var first = indexingService.ensureIndexed(versionId);
        assertThat(first.chunks()).isEqualTo(6);
        assertThat(first.embedded()).isEqualTo(6);
        assertThat(chunkCount(versionId)).isEqualTo(6);
        assertThat(jdbcTemplate.queryForList("SELECT chunk_key FROM resume_chunk WHERE resume_version_id = ? ORDER BY ordinal", String.class, versionId))
                .containsExactly("summary", "skills", "experience:0", "experience:1", "project:0", "education:0");

        var second = indexingService.ensureIndexed(versionId);
        assertThat(second.alreadyIndexed()).isTrue();
        assertThat(embeddingClient.embeddedTexts).hasValue(6);
    }

    /** 新版本只改了一段经历：只有这一块需要重新 embedding，其余复用 v1 的向量。 */
    @Test
    void newVersionShouldOnlyEmbedChangedChunks() {
        indexingService.ensureIndexed(insertVersion(1, RESUME));
        embeddingClient.embeddedTexts.set(0);
        Long v2 = insertVersion(2, RESUME.replace("Tuned slow PostgreSQL queries", "Rewrote slow PostgreSQL reports"));

        var result = indexingService.ensureIndexed(v2);

        assertThat(result.embedded()).isEqualTo(1);
        assertThat(result.reused()).isEqualTo(5);
        assertThat(embeddingClient.embeddedTexts).hasValue(1);
        assertThat(chunkCount(v2)).isEqualTo(6);
    }

    @Test
    void concurrentIndexingOfSameVersionShouldStoreOneCopy() throws Exception {
        Long versionId = insertVersion(1, RESUME);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return indexingService.ensureIndexed(versionId);
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            assertThat(chunkCount(versionId)).isEqualTo(6);
        } finally {
            executor.shutdownNow();
        }
    }

    /** pgvector 的排序必须和内存中精确计算余弦相似度的排序一致（没有用近似索引，应当完全相同）。 */
    @Test
    void pgvectorRankingShouldMatchExactCosine() throws Exception {
        Long versionId = insertVersion(1, RESUME);
        indexingService.ensureIndexed(versionId);
        float[] query = embeddingClient.embed(List.of("Build event streaming pipelines with Kafka")).get(0);

        var hits = chunkRepository.search(versionId, HashingEmbeddingClient.MODEL, query, 6);

        // 和查询没有共同词的块相似度都是 0，它们之间的先后顺序没有意义，所以比较的是每个块的相似度值和整体降序。
        var content = new com.fasterxml.jackson.databind.ObjectMapper().readTree(RESUME);
        java.util.Map<String, Double> exact = new java.util.HashMap<>();
        for (var chunk : chunker.chunk(content)) {
            exact.put(chunk.key(), HashingEmbeddingClient.cosine(query, embeddingClient.embed(List.of(chunk.content())).get(0)));
        }
        assertThat(hits).hasSize(6);
        for (var hit : hits) {
            assertThat(hit.similarity()).as(hit.chunkKey()).isCloseTo(exact.get(hit.chunkKey()), org.assertj.core.api.Assertions.within(1e-4));
        }
        assertThat(hits).extracting(ResumeChunkRepository.SearchHit::similarity)
                .isSortedAccordingTo(Comparator.<Double>reverseOrder());
        assertThat(hits.get(0).chunkKey()).isEqualTo("experience:0");
    }

    @Test
    void retrieverShouldMarkUnsupportedRequirementsAsNotCovered() {
        Long versionId = insertVersion(1, RESUME);
        indexingService.ensureIndexed(versionId);

        List<RequirementEvidence> evidence = retriever.retrieve(versionId, """
                - Experience building Kafka event pipelines
                - Native iOS development in Swift and SwiftUI
                """);

        assertThat(evidence).hasSize(2);
        assertThat(evidence.get(0).covered()).isTrue();
        assertThat(evidence.get(0).evidence().get(0).section()).isEqualTo("EXPERIENCE");
        assertThat(evidence.get(0).evidence().get(0).excerpt()).contains("Kafka event pipelines");
        assertThat(evidence.get(1).covered()).isFalse();
        assertThat(evidence.get(1).evidence()).isEmpty();
    }

    /** 两个模型的向量不在同一语义空间：按模型分别存储，检索只返回同一模型的块。 */
    @Test
    void embeddingsFromDifferentModelsShouldBeIsolated() {
        Long versionId = insertVersion(1, RESUME);
        indexingService.ensureIndexed(versionId);
        CountingEmbeddingClient otherModel = new CountingEmbeddingClient(new HashingEmbeddingClient(), "other-model-v1");
        ResumeIndexingService otherIndexer = new ResumeIndexingService(chunkRepository, resumeVersionRepository, chunker,
                otherModel, new SimpleMeterRegistry(), transactionManager);

        var result = otherIndexer.ensureIndexed(versionId);

        // 另一个模型的缓存不能命中：必须重新 embedding 全部块。
        assertThat(result.embedded()).isEqualTo(6);
        assertThat(chunkCount(versionId)).isEqualTo(12);
        float[] query = otherModel.embed(List.of("Kafka")).get(0);
        assertThat(chunkRepository.search(versionId, "other-model-v1", query, 20)).hasSize(6);
    }
}
