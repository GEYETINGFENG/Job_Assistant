package com.keny.jobassistant;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.dto.JobDTO;
import com.keny.jobassistant.model.dto.JobPageDTO;
import com.keny.jobassistant.model.dto.MatchDTO;
import com.keny.jobassistant.model.entity.request.JobCreateRequest;
import com.keny.jobassistant.model.entity.request.MatchCreateRequest;
import com.keny.jobassistant.model.enums.MatchEngine;
import com.keny.jobassistant.model.enums.MatchStatus;
import com.keny.jobassistant.model.task.MatchTaskClaim;
import com.keny.jobassistant.repository.MatchTaskRepository;
import com.keny.jobassistant.security.CurrentUserProvider;
import com.keny.jobassistant.service.JobService;
import com.keny.jobassistant.service.MatchQuotaService;
import com.keny.jobassistant.service.MatchService;
import com.keny.jobassistant.service.MatchWorker;
import com.keny.jobassistant.service.impl.JobServiceImpl;
import com.keny.jobassistant.service.impl.MatchServiceImpl;
import com.keny.jobassistant.config.EmbeddingConfig;
import com.keny.jobassistant.repository.ResumeChunkRepository;
import com.keny.jobassistant.service.match.KeywordMatchAnalyzer;
import com.keny.jobassistant.service.rag.RequirementExtractor;
import com.keny.jobassistant.service.rag.ResumeChunker;
import com.keny.jobassistant.service.rag.ResumeIndexingService;
import com.keny.jobassistant.service.rag.ResumeRetriever;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * JD 与匹配链路集成测试（Testcontainers PostgreSQL + 项目全部 Flyway 迁移）：
 * 1. 两个相同的发起请求并发：只产生一条任务，只扣一次额度
 * 2. worker 完成任务后，再次发起直接返回已有结果
 * 3. 两个 worker 并发领取：SKIP LOCKED 让它们拿到不同任务
 * 4. 额度不足时事务回滚，不留下任务
 * 5. 数据库约束拦截非法结果
 * 6. JD 游标分页
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
@Import({JobServiceImpl.class, MatchServiceImpl.class, MatchTaskRepository.class, KeywordMatchAnalyzer.class,
        MatchWorker.class, JacksonAutoConfiguration.class, EmbeddingConfig.class, ResumeChunkRepository.class,
        ResumeChunker.class, RequirementExtractor.class, ResumeIndexingService.class, ResumeRetriever.class,
        SimpleMeterRegistry.class})
@Tag("integration")
class MatchIntegrationTest {

    private static final long USER_ID = 1L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(TestDatabaseImages.POSTGRES_WITH_PGVECTOR)
            .withDatabaseName("jobassistant_match_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private JobService jobService;
    @Autowired
    private MatchService matchService;
    @Autowired
    private MatchWorker matchWorker;
    @Autowired
    private MatchTaskRepository matchTaskRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private CurrentUserProvider currentUserProvider;
    @MockBean
    private MatchQuotaService quotaService;

    private Long resumeVersionId;

    @BeforeEach
    void setUpData() {
        jdbcTemplate.execute("TRUNCATE resume_chunk, match_record, application_event, application, resume_version, resume, job, users RESTART IDENTITY CASCADE");
        jdbcTemplate.update("INSERT INTO users (id, user_account, user_password) VALUES (?, 'alice', 'hash')", USER_ID);
        Long resumeId = jdbcTemplate.queryForObject("""
                INSERT INTO resume (user_id, resume_name, latest_version_number) VALUES (?, 'Backend Resume', 1) RETURNING id
                """, Long.class, USER_ID);
        resumeVersionId = jdbcTemplate.queryForObject("""
                INSERT INTO resume_version (resume_id, version_number, content_json)
                VALUES (?, 1, '{"skills": ["Java", "Spring Boot", "PostgreSQL"], "experience": [{"company": "Example"}]}'::jsonb)
                RETURNING id
                """, Long.class, resumeId);
        when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
    }

    private JobDTO createJob(String title, String description) {
        JobCreateRequest request = new JobCreateRequest();
        request.setCompanyName("Example Corp");
        request.setJobTitle(title);
        request.setDescription(description);
        return jobService.createJob(request);
    }

    private MatchCreateRequest matchRequest(Long jobId) {
        MatchCreateRequest request = new MatchCreateRequest();
        request.setJobId(jobId);
        request.setResumeVersionId(resumeVersionId);
        return request;
    }

    private int matchCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM match_record", Integer.class);
    }

    @Test
    void concurrentIdenticalRequestsShouldCreateOneTaskAndConsumeQuotaOnce() throws Exception {
        Long jobId = createJob("Backend Engineer", "Java, Spring Boot, Kafka").getId();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MatchDTO>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return matchService.createMatch(matchRequest(jobId));
                }));
            }
            start.countDown();
            List<Long> ids = new ArrayList<>();
            for (Future<MatchDTO> future : futures) {
                ids.add(future.get(30, TimeUnit.SECONDS).getId());
            }

            assertThat(ids).hasSize(4).containsOnly(ids.get(0));
            assertThat(matchCount()).isEqualTo(1);
            verify(quotaService, times(1)).consume(USER_ID);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void workerShouldCompleteTaskAndRepeatRequestShouldReturnExistingResult() {
        Long jobId = createJob("Backend Engineer", "Java, Spring Boot, Kafka").getId();

        MatchDTO created = matchService.createMatch(matchRequest(jobId));
        assertThat(created.getStatus()).isEqualTo(MatchStatus.PENDING);
        assertThat(created.getEngine()).isEqualTo(MatchEngine.KEYWORD);

        matchWorker.processPendingMatches();

        MatchDTO completed = matchService.getMatch(created.getId());
        assertThat(completed.getStatus()).isEqualTo(MatchStatus.COMPLETED);
        assertThat(completed.getAnalysis().matchedSkills()).containsExactly("Java", "Spring Boot");
        assertThat(completed.getAnalysis().missingSkills()).containsExactly("Kafka");
        assertThat(completed.getScore()).isEqualTo(67);
        // RAG：JD 被拆成要求并在简历里检索证据。
        assertThat(completed.getAnalysis().requirements()).isNotEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM resume_chunk", Integer.class)).isPositive();
        assertThat(completed.getAttemptCount()).isEqualTo(1);

        MatchDTO repeated = matchService.createMatch(matchRequest(jobId));
        assertThat(repeated.getId()).isEqualTo(created.getId());
        assertThat(repeated.getStatus()).isEqualTo(MatchStatus.COMPLETED);
        verify(quotaService, times(1)).consume(USER_ID);
        assertThat(matchService.listMatches(jobId, null, 20).getItems()).extracting(MatchDTO::getId).containsExactly(created.getId());
    }

    /** 第一个 worker 领取后还没提交时，第二个 worker 应该跳过被锁的行，领取另一条任务而不是等待。 */
    @Test
    void concurrentWorkersShouldClaimDifferentTasks() throws Exception {
        matchService.createMatch(matchRequest(createJob("Backend A", "Java").getId()));
        matchService.createMatch(matchRequest(createJob("Backend B", "Java").getId()));
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch firstClaimed = new CountDownLatch(1);
            CountDownLatch secondDone = new CountDownLatch(1);
            Future<Optional<MatchTaskClaim>> first = executor.submit(() -> transactionTemplate.execute(status -> {
                Optional<MatchTaskClaim> claim = matchTaskRepository.claimNext(UUID.randomUUID());
                firstClaimed.countDown();
                await(secondDone);
                return claim;
            }));
            assertThat(firstClaimed.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Optional<MatchTaskClaim>> second = executor.submit(() -> transactionTemplate.execute(status -> {
                Optional<MatchTaskClaim> claim = matchTaskRepository.claimNext(UUID.randomUUID());
                secondDone.countDown();
                return claim;
            }));

            Optional<MatchTaskClaim> secondClaim = second.get(10, TimeUnit.SECONDS);
            Optional<MatchTaskClaim> firstClaim = first.get(10, TimeUnit.SECONDS);
            assertThat(firstClaim).isPresent();
            assertThat(secondClaim).isPresent();
            assertThat(secondClaim.get().matchId()).isNotEqualTo(firstClaim.get().matchId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void quotaExceededShouldRollBackTheInsertedTask() {
        Long jobId = createJob("Backend Engineer", "Java").getId();
        doThrow(new BusinessException(ErrorCode.QUOTA_EXCEEDED)).when(quotaService).consume(anyLong());

        assertThatThrownBy(() -> matchService.createMatch(matchRequest(jobId)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED.getCode()));
        assertThat(matchCount()).isZero();
    }

    @Test
    void databaseShouldRejectInvalidResults() {
        Long matchId = matchService.createMatch(matchRequest(createJob("Backend", "Java").getId())).getId();

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE match_record SET score = 101 WHERE id = ?", matchId))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 已完成的任务必须有结果。
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE match_record SET status = 'COMPLETED' WHERE id = ?", matchId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE match_record SET status = 'DONE' WHERE id = ?", matchId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void jobCursorPaginationShouldBeStableWhenNewJobsArrive() {
        List<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            ids.add(createJob("Job " + i, "Java").getId());
        }

        JobPageDTO first = jobService.listJobs(null, 2);
        assertThat(first.getItems()).extracting(JobDTO::getId).containsExactly(ids.get(4), ids.get(3));
        createJob("Job 6", "Java");
        JobPageDTO second = jobService.listJobs(first.getNextCursor(), 2);
        assertThat(second.getItems()).extracting(JobDTO::getId).containsExactly(ids.get(2), ids.get(1));
        JobPageDTO last = jobService.listJobs(second.getNextCursor(), 2);
        assertThat(last.getItems()).extracting(JobDTO::getId).containsExactly(ids.get(0));
        assertThat(last.isHasNext()).isFalse();
    }

    @Test
    void otherUsersJobShouldNotBeVisible() {
        Long jobId = createJob("Backend", "Java").getId();
        jdbcTemplate.update("INSERT INTO users (id, user_account, user_password) VALUES (2, 'bob', 'hash')");
        when(currentUserProvider.getCurrentUserId()).thenReturn(2L);

        assertThatThrownBy(() -> jobService.getJob(jobId)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> matchService.createMatch(matchRequest(jobId))).isInstanceOf(BusinessException.class);
        assertThat(matchCount()).isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the other worker");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
