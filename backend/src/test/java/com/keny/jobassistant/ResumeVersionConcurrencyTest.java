package com.keny.jobassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.keny.jobassistant.repository.ResumeVersionAtomicRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resume version_number 并发测试。
 *
 * 测试目标：
 * 多个线程同时给同一个 Resume 创建新版本时，
 * version_number 必须连续，并且不能重复。
 * 本测试使用 Testcontainers 创建临时 PostgreSQL，
 * 不会连接或修改开发数据库。
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never",
        "spring.datasource.hikari.maximum-pool-size=12"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ResumeVersionAtomicRepository.class)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ResumeVersionConcurrencyTest {

    /**
     * 模拟的用户ID。
     * 测试数据库中的 resume 表没有创建 users 外键，
     * 因为当前测试只验证版本号并发控制。
     */
    private static final Long TEST_USER_ID = 100L;

    /**
     * 同时增加版本的线程数量。
     */
    private static final int THREAD_COUNT = 10;

    /**
     * Testcontainers 启动的临时 PostgreSQL。
     * 测试开始：
     * 自动启动 Docker PostgreSQL。
     * 测试结束：
     * 自动停止并删除整个容器。
     */
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("jobassistant_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * 把 Spring Boot 数据源指向 Testcontainers，
     * 而不是 application.yml 中配置的开发数据库。
     */
    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
    }

    @Autowired
    private ResumeVersionAtomicRepository resumeVersionAtomicRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private JdbcTemplate jdbcTemplate;

    /**
     * 当前测试使用的临时 resumeId。
     */
    private Long resumeId;

    @Autowired
    void setDataSource(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * 每次测试前重新创建最小数据库结构。
     * 这里只创建原子SQL真正依赖的：
     * resume
     * resume_version
     * 不加载开发数据库，不依赖 users、S3 等其他表。
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
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

        jdbcTemplate.execute("""
                CREATE TABLE resume_version (
                    id BIGSERIAL PRIMARY KEY,
                    resume_id BIGINT NOT NULL,
                    version_number INTEGER NOT NULL,
                    content_json JSONB,
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT fk_version_resume
                        FOREIGN KEY (resume_id)
                        REFERENCES resume(id)
                        ON DELETE CASCADE
                )
                """);

        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX uk_resume_version
                ON resume_version(resume_id, version_number)
                """);

        /*
         * 创建一份已经存在 V1 的 Resume。
         * 后面的 10 个线程应该继续创建：
         * V2 ~ V11。
         */
        resumeId = jdbcTemplate.queryForObject("""
                INSERT INTO resume (
                    user_id,
                    resume_name,
                    file_url,
                    parsed_json,
                    status,
                    latest_version_number,
                    create_time,
                    update_time
                )
                VALUES (
                    ?,
                    'Concurrency Test Resume',
                    '/resumes/test/file',
                    '{"version": 1}'::jsonb,
                    0,
                    1,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP
                )
                RETURNING id
                """, Long.class, TEST_USER_ID);

        assertNotNull(resumeId);

        jdbcTemplate.update("""
                INSERT INTO resume_version (
                    resume_id,
                    version_number,
                    content_json,
                    create_time
                )
                VALUES (?, 1, '{"version": 1}'::jsonb, CURRENT_TIMESTAMP)
                """, resumeId);
    }

    /**
     * 10 个线程同时给同一个 Resume 创建版本。
     * 初始：V1
     * 并发完成后：V1...V11
     * 并且 latest_version_number = 11。
     */
    @Test
    void shouldCreateUniqueSequentialVersionsWhenConcurrentRequestsArrive() throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(THREAD_COUNT);

        //readyLatch：等所有线程全部准备完成。
        //startLatch：让10个线程停在同一条起跑线上，然后同时开始执行原子SQL。
        CountDownLatch readyLatch = new CountDownLatch(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < THREAD_COUNT; i++) {
                int threadNumber = i;
                futures.add(executorService.submit(() -> {
                    readyLatch.countDown();
                    if (!startLatch.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for concurrent test start");
                    }
                    // 每一个线程建立自己的数据库事务
                    TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
                    Integer versionNumber = transactionTemplate.execute(status -> {
                        JsonNode contentJson = JsonNodeFactory.instance.objectNode().put("thread", threadNumber);
                        return resumeVersionAtomicRepository.createNextVersion(
                                resumeId,
                                TEST_USER_ID,
                                "Concurrency Test Resume",
                                "/resumes/" + resumeId + "/file",
                                contentJson
                        ).orElseThrow(() -> new IllegalStateException("Failed to create resume version"));
                    });
                    if (versionNumber == null) {
                        throw new IllegalStateException("Version number cannot be null");
                    }
                    return versionNumber;
                }));
            }

            //等待10个线程全部准备好
            assertTrue(readyLatch.await(10, TimeUnit.SECONDS), "Not all worker threads became ready");
            //同时放行10个线程
            startLatch.countDown();
            //收集10个线程得到的版本号
            List<Integer> returnedVersions = new ArrayList<>();
            for (Future<Integer> future : futures) {
                returnedVersions.add(future.get(30, TimeUnit.SECONDS));
            }
            Collections.sort(returnedVersions);

            // 10个线程应该分别获得：V2 ~ V11
            List<Integer> expectedReturnedVersions = IntStream.rangeClosed(2, THREAD_COUNT + 1)
                    .boxed()
                    .toList();
            assertEquals(expectedReturnedVersions, returnedVersions);

            // 查询 resume_version，数据库最终必须存在 V1 ~ V11。
            List<Integer> databaseVersions = jdbcTemplate.query("""
                    SELECT version_number
                    FROM resume_version
                    WHERE resume_id = ?
                    ORDER BY version_number
                    """, (resultSet, rowNumber) -> resultSet.getInt("version_number"), resumeId);
            List<Integer> expectedDatabaseVersions = IntStream.rangeClosed(1, THREAD_COUNT + 1)
                    .boxed()
                    .toList();
            assertEquals(expectedDatabaseVersions, databaseVersions);
            //再验证没有重复版本号
            long distinctVersionCount = databaseVersions.stream().distinct().count();
            assertEquals(THREAD_COUNT + 1, distinctVersionCount);
            //Resume 主表中的最新版本号也必须正确
            Integer latestVersionNumber = jdbcTemplate.queryForObject("""
                    SELECT latest_version_number
                    FROM resume
                    WHERE id = ?
                    """, Integer.class, resumeId);

            assertEquals(THREAD_COUNT + 1, latestVersionNumber);
            System.out.println("========================================");
            System.out.println("Resume concurrency test passed");
            System.out.println("Temporary PostgreSQL container: " + POSTGRES.getContainerId());
            System.out.println("Resume ID: " + resumeId);
            System.out.println("Returned versions: " + returnedVersions);
            System.out.println("Database versions: " + databaseVersions);
            System.out.println("Latest version: " + latestVersionNumber);
            System.out.println("========================================");
        } finally {
            /*
             * 如果测试中途异常，
             * 也释放正在等待的线程。
             */
            startLatch.countDown();
            executorService.shutdownNow();
        }
    }
}