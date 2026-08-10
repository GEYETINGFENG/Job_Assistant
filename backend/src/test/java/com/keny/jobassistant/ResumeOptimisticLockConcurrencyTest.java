package com.keny.jobassistant;

import com.keny.jobassistant.model.entity.Resume;
import com.keny.jobassistant.repository.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resume @Version 乐观锁并发测试。
 * 两个事务同时读取同一条 Resume，
 * 然后同时修改：其中一个应该成功，另一个应该发生乐观锁冲突。
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.datasource.hikari.maximum-pool-size=5"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ResumeOptimisticLockConcurrencyTest {

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
    private ResumeRepository resumeRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcTemplate jdbcTemplate;
    private Long resumeId;

    @Autowired
    void setDataSource(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * 每次测试前创建最小 resume 表。
     */
    @BeforeEach
    void setUpDatabase() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS resume_version");
        jdbcTemplate.execute("DROP TABLE IF EXISTS resume");

        jdbcTemplate.execute("""
                CREATE TABLE resume (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    resume_name VARCHAR(256),
                    file_url VARCHAR(1024),
                    parsed_json JSONB,
                    status INTEGER DEFAULT 0,
                    latest_version_number INTEGER NOT NULL DEFAULT 0,
                    lock_version BIGINT NOT NULL DEFAULT 0,
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

        resumeId = jdbcTemplate.queryForObject("""
                INSERT INTO resume (
                    user_id,
                    resume_name,
                    status,
                    latest_version_number,
                    lock_version
                )
                VALUES (100, 'Original Resume', 0, 1, 0)
                RETURNING id
                """, Long.class);

        assertNotNull(resumeId);
    }

    /**
     * 两个事务同时修改同一份 Resume：
     * 一个成功，
     * 一个必须发生乐观锁冲突。
     */
    @Test
    void shouldAllowOnlyOneConcurrentUpdate() throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        /*
         * 两个线程必须先都读到 lock_version = 0，
         * 再同时开始 UPDATE。
         */
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Callable<String> firstUpdate = createUpdateTask("Resume Updated By Thread A", readyLatch, startLatch);
        Callable<String> secondUpdate = createUpdateTask("Resume Updated By Thread B", readyLatch, startLatch);

        Future<String> firstFuture = executorService.submit(firstUpdate);
        Future<String> secondFuture = executorService.submit(secondUpdate);

        try {
            assertTrue(readyLatch.await(10, TimeUnit.SECONDS), "Both threads should read the Resume before updating");

            // 两个线程同时开始 UPDATE。
            startLatch.countDown();
            List<String> results = List.of(
                    firstFuture.get(30, TimeUnit.SECONDS),
                    secondFuture.get(30, TimeUnit.SECONDS)
            );

            //必须刚好：一个 SUCCESS 一个 CONFLICT
            long successCount = results.stream().filter("SUCCESS"::equals).count();
            long conflictCount = results.stream().filter("CONFLICT"::equals).count();
            assertEquals(1, successCount);
            assertEquals(1, conflictCount);

            // 初始 lock_version = 0。只有一个 UPDATE 成功，所以最终只能变成 1
            Long lockVersion = jdbcTemplate.queryForObject(
                    "SELECT lock_version FROM resume WHERE id = ?",
                    Long.class,
                    resumeId
            );
            assertEquals(1L, lockVersion);
            String resumeName = jdbcTemplate.queryForObject(
                    "SELECT resume_name FROM resume WHERE id = ?",
                    String.class,
                    resumeId
            );
            assertTrue(
                    "Resume Updated By Thread A".equals(resumeName)
                            || "Resume Updated By Thread B".equals(resumeName)
            );
            System.out.println("========================================");
            System.out.println("Optimistic lock concurrency test passed");
            System.out.println("Results: " + results);
            System.out.println("Final resume name: " + resumeName);
            System.out.println("Final lock version: " + lockVersion);
            System.out.println("========================================");
        } finally {
            startLatch.countDown();
            executorService.shutdownNow();
        }
    }

    /**
     * 创建一个并发更新任务。
     */
    private Callable<String> createUpdateTask(String newResumeName, CountDownLatch readyLatch, CountDownLatch startLatch) {
        return () -> {
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
            try {
                return transactionTemplate.execute(status -> {
                    Resume resume = resumeRepository.findById(resumeId).orElseThrow();
                    // 两个线程在这里都应该读到 lockVersion = 0。
                    assertEquals(0L, resume.getLockVersion());
                    readyLatch.countDown();
                    try {
                        if (!startLatch.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Timed out waiting for concurrent update start");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Concurrent test was interrupted", exception);
                    }
                    resume.setResumeName(newResumeName);
                    /*
                     * 强制立即发送 UPDATE。
                     * Hibernate 会使用：
                     * WHERE id = ?
                     * AND lock_version = 0
                     * 第一个成功以后 lock_version = 1，
                     * 第二个 UPDATE 将匹配不到记录并发生乐观锁异常。
                     */
                    resumeRepository.saveAndFlush(resume);
                    return "SUCCESS";
                });
            } catch (ObjectOptimisticLockingFailureException exception) {
                return "CONFLICT";
            }
        };
    }
}