package com.keny.jobassistant;

import com.keny.jobassistant.model.entity.Resume;
import com.keny.jobassistant.repository.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resume 软删除与版本引用关系集成测试。
 * 使用 Testcontainers 启动一个临时 PostgreSQL，
 * 不会连接或修改本地开发数据库。
 * 主要验证：
 * 1. 删除 Resume 时只执行软删除；
 * 2. ResumeVersion 不会被删除；
 * 3. Application 不会被删除；
 * 4. Application 仍然引用原来的 ResumeVersion；
 * 5. Application 正在引用 ResumeVersion 时，
 *    数据库 RESTRICT 会阻止物理删除 ResumeVersion；
 * 6. Resume 仍然存在 ResumeVersion 时，
 *    数据库 RESTRICT 会阻止物理删除 Resume。
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ResumeSoftDeleteIntegrationTest {

    /**
     * 测试专用 PostgreSQL。
     * 测试启动时 Docker 创建，
     * 测试结束后由 Testcontainers 清理。
     */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jobassistant_soft_delete_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * 把 Spring 测试数据源指向 Docker PostgreSQL。
     * 因此这里不会使用 application.yml 中的本地数据库地址。
     */
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
    private ResumeRepository resumeRepository;

    private Long userId;
    private Long resumeId;
    private Long resumeVersionId;
    private Long jobId;
    private Long applicationId;

    /**
     * 每次测试前只在 Docker PostgreSQL 中创建最小测试数据库结构。
     * 不执行项目 Flyway，
     * 也不会连接本地 PostgreSQL。
     */
    @BeforeEach
    void setUpDatabase() {
        /*
         * 必须按照外键依赖关系从子表往父表删除。
         */
        jdbcTemplate.execute("DROP TABLE IF EXISTS application");
        jdbcTemplate.execute("DROP TABLE IF EXISTS resume_version");
        jdbcTemplate.execute("DROP TABLE IF EXISTS job");
        jdbcTemplate.execute("DROP TABLE IF EXISTS resume");
        jdbcTemplate.execute("DROP TABLE IF EXISTS users");

        /*
         * 创建最小 users 表。
         * 当前测试只需要 users.id，不需要完整用户业务字段。
         */
        jdbcTemplate.execute("""
                CREATE TABLE users (
                    id BIGSERIAL PRIMARY KEY
                )
                """);

        /*
         * 创建最小 resume 表。
         * 字段与 Resume Entity 当前需要读取的字段保持一致。
         */
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
                    is_delete INTEGER NOT NULL DEFAULT 0,
                    delete_time TIMESTAMP,
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                    CONSTRAINT fk_resume_user
                    FOREIGN KEY(user_id)
                    REFERENCES users(id)
                    ON DELETE CASCADE,

                    CONSTRAINT ck_resume_is_delete
                    CHECK (is_delete IN (0, 1))
                )
                """);

        /*
         * Resume -> ResumeVersion 使用 RESTRICT。
         * 只要 Resume 还有历史版本，
         * 就禁止直接物理删除 Resume。
         */
        jdbcTemplate.execute("""
                CREATE TABLE resume_version (
                    id BIGSERIAL PRIMARY KEY,
                    resume_id BIGINT NOT NULL,
                    version_number INTEGER NOT NULL,
                    content_json JSONB,
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                    CONSTRAINT fk_version_resume
                    FOREIGN KEY(resume_id)
                    REFERENCES resume(id)
                    ON DELETE RESTRICT,

                    CONSTRAINT uk_resume_version_number
                    UNIQUE(resume_id, version_number)
                )
                """);

        /*
         * 创建最小 Job 表。
         * Application 的 job_id 是 NOT NULL，
         * 所以测试中仍然需要一条 Job 数据。
         */
        jdbcTemplate.execute("""
                CREATE TABLE job (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    company_name VARCHAR(256) NOT NULL,
                    job_title VARCHAR(256) NOT NULL,
                    description TEXT,
                    requirements JSONB,
                    location VARCHAR(256),
                    salary VARCHAR(128),
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                    CONSTRAINT fk_job_user
                    FOREIGN KEY(user_id)
                    REFERENCES users(id)
                    ON DELETE CASCADE
                )
                """);

        /*
         * Application -> ResumeVersion 使用 RESTRICT。
         * Application 正在引用某个 ResumeVersion 时，
         * 禁止物理删除这个 ResumeVersion。
         */
        jdbcTemplate.execute("""
                CREATE TABLE application (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    job_id BIGINT NOT NULL,
                    resume_version_id BIGINT,
                    status INTEGER DEFAULT 0,
                    apply_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                    CONSTRAINT fk_application_user
                    FOREIGN KEY(user_id)
                    REFERENCES users(id)
                    ON DELETE CASCADE,

                    CONSTRAINT fk_application_job
                    FOREIGN KEY(job_id)
                    REFERENCES job(id)
                    ON DELETE CASCADE,

                    CONSTRAINT fk_application_resume_version
                    FOREIGN KEY(resume_version_id)
                    REFERENCES resume_version(id)
                    ON DELETE RESTRICT,

                    CONSTRAINT uk_application_user_job
                    UNIQUE(user_id, job_id)
                )
                """);

        // 插入测试用户
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO users DEFAULT VALUES
                RETURNING id
                """, Long.class);
        assertNotNull(userId);
        /*
         * 创建一份正常 Resume,当前最新业务版本号设为 3。
         */
        resumeId = jdbcTemplate.queryForObject("""
                INSERT INTO resume (
                    user_id,
                    resume_name,
                    status,
                    latest_version_number,
                    lock_version,
                    is_delete
                )
                VALUES (?, 'S3 PDF Test03', 0, 3, 0, 0)
                RETURNING id
                """, Long.class, userId);
        assertNotNull(resumeId);

        /*
         * 创建 Resume V3。
         */
        resumeVersionId = jdbcTemplate.queryForObject("""
                INSERT INTO resume_version (
                    resume_id,
                    version_number,
                    content_json
                )
                VALUES (?, 3, '{"name":"S3 PDF Test03"}'::jsonb)
                RETURNING id
                """, Long.class, resumeId);

        assertNotNull(resumeVersionId);
        /*
         * 创建一条 Job。
         */
        jobId = jdbcTemplate.queryForObject("""
                INSERT INTO job (
                    user_id,
                    company_name,
                    job_title
                )
                VALUES (?, 'Google', 'Software Engineer')
                RETURNING id
                """, Long.class, userId);

        assertNotNull(jobId);
        /*
         * 创建 Application，并明确引用上面的 Resume V3。
         */
        applicationId = jdbcTemplate.queryForObject("""
                INSERT INTO application (
                    user_id,
                    job_id,
                    resume_version_id,
                    status
                )
                VALUES (?, ?, ?, 0)
                RETURNING id
                """, Long.class, userId, jobId, resumeVersionId);
        assertNotNull(applicationId);
    }

    /**
     * 核心测试：
     * Resume 被软删除以后：
     * Resume 行仍然存在
     * ResumeVersion 仍然存在
     * Application 仍然存在
     * Application -> ResumeVersion 引用仍然存在
     */
    @Test
    void shouldKeepResumeVersionAndApplicationWhenResumeIsSoftDeleted() {
        // 1. 验证测试初始状态
        Integer initialResumeDeleteStatus = jdbcTemplate.queryForObject(
                "SELECT is_delete FROM resume WHERE id = ?",
                Integer.class,
                resumeId
        );
        assertEquals(Resume.NOT_DELETED, initialResumeDeleteStatus);

        Integer initialResumeVersionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resume_version WHERE resume_id = ?",
                Integer.class,
                resumeId
        );
        assertEquals(1, initialResumeVersionCount);

        Integer initialApplicationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM application WHERE id = ?",
                Integer.class,
                applicationId
        );
        assertEquals(1, initialApplicationCount);

        /*
         * 2. 模拟真实软删除逻辑
         * 这里使用 ResumeRepository，与 ResumeServiceImpl.deleteResume() 的核心逻辑一致：
         * 找到当前用户未删除的 Resume，
         * 然后只修改 isDelete/deleteTime/updateTime，
         * 不调用 repository.delete()。
         */

        Resume resume = resumeRepository
                .findByIdAndUser_IdAndIsDelete(resumeId, userId, Resume.NOT_DELETED)
                .orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        resume.setIsDelete(Resume.DELETED);
        resume.setDeleteTime(now);
        resume.setUpdateTime(now);
        resumeRepository.saveAndFlush(resume);

        /*
         * 3. Resume 本身没有被物理删除
         */

        Integer resumeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resume WHERE id = ?",
                Integer.class,
                resumeId
        );
        assertEquals(1, resumeCount);

        /*
         * 但 is_delete 已经从 0 变成 1。
         */
        Integer isDelete = jdbcTemplate.queryForObject(
                "SELECT is_delete FROM resume WHERE id = ?",
                Integer.class,
                resumeId
        );
        assertEquals(Resume.DELETED, isDelete);
        /*
         * delete_time 也应该已经记录。
         */
        LocalDateTime deleteTime = jdbcTemplate.queryForObject(
                "SELECT delete_time FROM resume WHERE id = ?",
                LocalDateTime.class,
                resumeId
        );
        assertNotNull(deleteTime);

        /*
         * 4. 普通 Resume 查询已经看不到这份简历
         * 因为用户侧 Resume API 查询要求：
         * id = ?
         * user_id = ?
         * is_delete = 0
         */

        assertTrue(
                resumeRepository
                        .findByIdAndUser_IdAndIsDelete(resumeId, userId, Resume.NOT_DELETED)
                        .isEmpty()
        );

        /*
         * 5. ResumeVersion 必须继续存在
         */

        Integer resumeVersionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resume_version WHERE id = ?",
                Integer.class,
                resumeVersionId
        );

        assertEquals(1, resumeVersionCount);

        /*
         * 6. Application 必须继续存在
         */

        Integer applicationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM application WHERE id = ?",
                Integer.class,
                applicationId
        );
        assertEquals(1, applicationCount);

        /*
         * 7. Application 仍然引用原来的 ResumeVersion
         */

        Long referencedResumeVersionId = jdbcTemplate.queryForObject(
                "SELECT resume_version_id FROM application WHERE id = ?",
                Long.class,
                applicationId
        );

        assertEquals(resumeVersionId, referencedResumeVersionId);

        /*
         * 8. 即使 Resume 已软删除，历史 Application 仍然能通过关系找到投递时的版本
         */

        Integer historicalVersionNumber = jdbcTemplate.queryForObject("""
                SELECT rv.version_number
                FROM application a
                JOIN resume_version rv
                  ON a.resume_version_id = rv.id
                JOIN resume r
                  ON rv.resume_id = r.id
                WHERE a.id = ?
                """, Integer.class, applicationId);

        assertEquals(3, historicalVersionNumber);

        /*
         * 同时确认 Resume 本身已经处于软删除状态。
         * 这证明：Resume 对普通用户已经删除，但 Application 的历史版本引用仍然完整。
         */
        Integer historicalResumeDeleteStatus = jdbcTemplate.queryForObject("""
                SELECT r.is_delete
                FROM application a
                JOIN resume_version rv
                  ON a.resume_version_id = rv.id
                JOIN resume r
                  ON rv.resume_id = r.id
                WHERE a.id = ?
                """, Integer.class, applicationId);

        assertEquals(Resume.DELETED, historicalResumeDeleteStatus);
        System.out.println("========================================");
        System.out.println("Resume soft delete test passed");
        System.out.println("Resume id: " + resumeId);
        System.out.println("Resume is_delete: " + isDelete);
        System.out.println("ResumeVersion id: " + resumeVersionId);
        System.out.println("Application id: " + applicationId);
        System.out.println("Application still references ResumeVersion: " + referencedResumeVersionId);
        System.out.println("========================================");
    }

    /**
     * 数据库保护测试：
     * Application 正在引用 ResumeVersion 时，
     * ON DELETE RESTRICT 必须阻止物理删除 ResumeVersion。
     */
    @Test
    void shouldRejectPhysicalDeleteOfResumeVersionReferencedByApplication() {
        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "DELETE FROM resume_version WHERE id = ?",
                        resumeVersionId
                )
        );

        assertNotNull(exception);
        /*
         * 删除失败后 ResumeVersion 仍然存在。
         */
        Integer resumeVersionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resume_version WHERE id = ?",
                Integer.class,
                resumeVersionId
        );
        assertEquals(1, resumeVersionCount);
        /*
         * Application 同样仍然存在。
         */
        Integer applicationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM application WHERE id = ?",
                Integer.class,
                applicationId
        );

        assertEquals(1, applicationCount);
    }

    /**
     * 数据库保护测试：
     * Resume 下面仍然存在 ResumeVersion 时，
     * ON DELETE RESTRICT 必须阻止直接物理删除 Resume。
     */
    @Test
    void shouldRejectPhysicalDeleteOfResumeWhenVersionsStillExist() {
        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "DELETE FROM resume WHERE id = ?",
                        resumeId
                )
        );

        assertNotNull(exception);
        /*
         * Resume 物理删除失败。
         */
        Integer resumeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resume WHERE id = ?",
                Integer.class,
                resumeId
        );
        assertEquals(1, resumeCount);
        /*
         * ResumeVersion 也仍然存在。
         */
        Integer resumeVersionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM resume_version WHERE resume_id = ?",
                Integer.class,
                resumeId
        );

        assertEquals(1, resumeVersionCount);
    }
}