package com.keny.jobassistant.repository;

import com.keny.jobassistant.model.task.ResumeUploadTaskClaim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实 PostgreSQL 验证任务领取 SQL 的筛选、加锁和原子状态更新。 */
@JdbcTest(properties = "spring.flyway.enabled=false")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ResumeUploadTaskClaimRepository.class)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ResumeUploadTaskClaimRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jobassistant_test")
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
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ResumeUploadTaskClaimRepository taskClaimRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DataSource dataSource;

    /** 每次测试建立领取 SQL 所需的最小表结构，不连接也不修改开发数据库。 */
    @BeforeEach
    void setUpDatabase() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS resume_upload_session");
        jdbcTemplate.execute("""
                CREATE TABLE resume_upload_session (
                    id UUID PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    resume_name VARCHAR(256) NOT NULL,
                    original_filename VARCHAR(256) NOT NULL,
                    upload_object_key VARCHAR(512),
                    processing_object_key VARCHAR(512),
                    expected_extension VARCHAR(10) NOT NULL,
                    expected_content_type VARCHAR(128) NOT NULL,
                    expected_size BIGINT NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    upload_type VARCHAR(20) NOT NULL,
                    resume_id BIGINT,
                    attempt_count INTEGER NOT NULL DEFAULT 0,
                    next_attempt_at TIMESTAMPTZ,
                    processing_started_at TIMESTAMPTZ,
                    claim_token UUID,
                    create_time TIMESTAMPTZ NOT NULL,
                    update_time TIMESTAMPTZ NOT NULL
                )
                """);
    }

    /** 只能领取已经到期且存在冻结对象的 PENDING 任务，并在同一条 SQL 中切换到 PROCESSING。 */
    @Test
    void claimNextShouldAtomicallyClaimOnlyEligibleTask() {
        UUID eligibleId = insertTask(Instant.now().minusSeconds(30), "resume-processing/eligible.pdf");
        insertTask(Instant.now().plusSeconds(3600), "resume-processing/future.pdf");
        insertTask(Instant.now().minusSeconds(60), null);
        UUID claimToken = UUID.randomUUID();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        Optional<ResumeUploadTaskClaim> result = transactionTemplate.execute(status -> taskClaimRepository.claimNext(claimToken));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().uploadId()).isEqualTo(eligibleId);
        assertThat(result.orElseThrow().claimToken()).isEqualTo(claimToken);
        assertThat(result.orElseThrow().attemptCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM resume_upload_session WHERE id = ?", String.class, eligibleId))
                .isEqualTo("PROCESSING");
        assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM resume_upload_session WHERE id = ?", Integer.class, eligibleId))
                .isEqualTo(1);
    }

    /** 一个 worker 锁住最早任务时，另一个 worker 应跳过该行并立即领取下一条任务。 */
    @Test
    void claimNextShouldSkipTaskLockedByAnotherWorker() throws Exception {
        UUID lockedTaskId = insertTask(Instant.now().minusSeconds(120), "resume-processing/locked.pdf");
        UUID availableTaskId = insertTask(Instant.now().minusSeconds(60), "resume-processing/available.pdf");
        UUID claimToken = UUID.randomUUID();
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        try (Connection lockingConnection = dataSource.getConnection()) {
            lockingConnection.setAutoCommit(false);
            try (PreparedStatement statement = lockingConnection.prepareStatement(
                    "SELECT id FROM resume_upload_session WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, lockedTaskId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    Optional<ResumeUploadTaskClaim> result = transactionTemplate.execute(status -> taskClaimRepository.claimNext(claimToken));

                    assertThat(result).isPresent();
                    assertThat(result.orElseThrow().uploadId()).isEqualTo(availableTaskId);
                    assertThat(jdbcTemplate.queryForObject("SELECT status FROM resume_upload_session WHERE id = ?", String.class, lockedTaskId))
                            .isEqualTo("PENDING");
                }
            } finally {
                // 测试结束回滚行锁事务，避免影响同一容器内的后续测试。
                lockingConnection.rollback();
            }
        }
    }

    /** 插入一个 CREATE 上传任务，并返回它的 uploadId。 */
    private UUID insertTask(Instant nextAttemptAt, String processingObjectKey) {
        UUID uploadId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO resume_upload_session (
                    id, user_id, resume_name, original_filename, upload_object_key, processing_object_key,
                    expected_extension, expected_content_type, expected_size, status, upload_type,
                    attempt_count, next_attempt_at, create_time, update_time
                ) VALUES (?, 7, 'Backend Resume', 'resume.pdf', ?, ?, '.pdf', 'application/pdf', 4,
                          'PENDING', 'CREATE', 0, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, uploadId, "resume-uploads/7/" + uploadId + "/source.pdf", processingObjectKey, Timestamp.from(nextAttemptAt));
        return uploadId;
    }
}
