package com.keny.jobassistant;

import com.keny.jobassistant.model.dto.ResumeSummaryDTO;
import com.keny.jobassistant.repository.ResumeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 简历列表分页查询集成测试。
 * 使用 Testcontainers 临时 PostgreSQL，验证：
 * 1. 只返回当前用户未删除的简历；
 * 2. 按 update_time 倒序，相同时间按 id 倒序，翻页结果稳定；
 * 3. hasNext 正确反映是否还有下一页。
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.sql.init.mode=never"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ResumeListQueryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jobassistant_list_test")
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
    private ResumeRepository resumeRepository;

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 12, 0);

    /** 每次测试重建最小表结构：用户 1 有 4 份有效简历和 1 份已删除简历，用户 2 有 1 份简历。 */
    @BeforeEach
    void setUpDatabase() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS resume");
        jdbcTemplate.execute("DROP TABLE IF EXISTS users");
        jdbcTemplate.execute("CREATE TABLE users (id BIGSERIAL PRIMARY KEY)");
        jdbcTemplate.execute("""
                CREATE TABLE resume (
                    id BIGSERIAL PRIMARY KEY,
                    user_id BIGINT NOT NULL REFERENCES users(id),
                    resume_name VARCHAR(256),
                    file_url VARCHAR(1024),
                    parsed_json JSONB,
                    status INTEGER DEFAULT 0,
                    latest_version_number INTEGER NOT NULL DEFAULT 0,
                    lock_version BIGINT NOT NULL DEFAULT 0,
                    is_delete INTEGER NOT NULL DEFAULT 0,
                    delete_time TIMESTAMP,
                    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.update("INSERT INTO users (id) VALUES (1), (2)");

        insertResume(1, "oldest", 0, BASE_TIME.minusDays(3));
        insertResume(1, "deleted", 1, BASE_TIME.plusDays(1));
        insertResume(1, "tie-a", 0, BASE_TIME);
        insertResume(1, "tie-b", 0, BASE_TIME);
        insertResume(1, "newest", 0, BASE_TIME.plusHours(1));
        insertResume(2, "other-user", 0, BASE_TIME.plusDays(2));
    }

    /** 第一页按更新时间倒序，update_time 相同时 id 大的在前，并且提示还有下一页。 */
    @Test
    void firstPageShouldBeOrderedByUpdateTimeThenId() {
        Slice<ResumeSummaryDTO> page = resumeRepository.findActiveSummariesByUserId(1L, PageRequest.of(0, 3));

        assertThat(page.getContent()).extracting(ResumeSummaryDTO::getResumeName)
                .containsExactly("newest", "tie-b", "tie-a");
        assertThat(page.hasNext()).isTrue();
    }

    /** 最后一页只剩一条，且已删除简历和其他用户的简历都不会出现。 */
    @Test
    void lastPageShouldExcludeDeletedAndOtherUsersResumes() {
        Slice<ResumeSummaryDTO> page = resumeRepository.findActiveSummariesByUserId(1L, PageRequest.of(1, 3));

        assertThat(page.getContent()).extracting(ResumeSummaryDTO::getResumeName).containsExactly("oldest");
        assertThat(page.hasNext()).isFalse();
    }

    private void insertResume(long userId, String name, int isDelete, LocalDateTime updateTime) {
        jdbcTemplate.update("""
                        INSERT INTO resume (user_id, resume_name, is_delete, latest_version_number, create_time, update_time)
                        VALUES (?, ?, ?, 1, ?, ?)
                        """,
                userId, name, isDelete, Timestamp.valueOf(updateTime.minusDays(10)), Timestamp.valueOf(updateTime));
    }
}
