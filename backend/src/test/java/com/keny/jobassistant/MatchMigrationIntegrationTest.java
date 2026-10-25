package com.keny.jobassistant;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V18 迁移测试：先迁移到 V17 写入旧格式 match_record，再升级到最新版本，验证回填、去重和清理规则。
 */
@Testcontainers
@Tag("integration")
class MatchMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("jobassistant_match_migration_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void legacyMatchRecordsShouldBeBackfilledDeduplicatedAndCleaned() {
        DataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        flyway(dataSource, "17").migrate();
        jdbc.update("INSERT INTO users (id, user_account, user_password) VALUES (1, 'alice', 'hash')");
        jdbc.update("INSERT INTO job (id, user_id, company_name, job_title, create_time) VALUES (1, 1, 'Google', 'SWE', NULL)");
        // resume 1 有两个版本；resume 2 没有任何版本。
        jdbc.update("INSERT INTO resume (id, user_id, resume_name, latest_version_number) VALUES (1, 1, 'A', 2), (2, 1, 'B', 0)");
        jdbc.update("INSERT INTO resume_version (id, resume_id, version_number, content_json) VALUES (10, 1, 1, '{}'), (11, 1, 2, '{}')");
        jdbc.update("""
                INSERT INTO match_record (id, resume_id, job_id, score, analysis_json) VALUES
                    (1, 1, 1, 80, '{"summary": "good"}'),   -- 完整结果
                    (2, 1, 1, NULL, NULL),                    -- 同一组合、更新但不完整：去重时不能把 1 挤掉
                    (3, 2, 1, 70, '{"summary": "x"}'),        -- 简历没有版本，无法回填
                    (4, 1, 1, 150, '{"summary": "bad"}')      -- 分数越界
                """);

        flyway(dataSource, "latest").migrate();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, user_id, resume_version_id, status, score, engine, prompt_version FROM match_record ORDER BY id");
        assertThat(rows).hasSize(1);
        Map<String, Object> kept = rows.get(0);
        assertThat(kept.get("id")).isEqualTo(1L);
        assertThat(kept.get("user_id")).isEqualTo(1L);
        // 回填为该简历的最新版本。
        assertThat(kept.get("resume_version_id")).isEqualTo(11L);
        assertThat(kept.get("status")).isEqualTo("COMPLETED");
        assertThat(kept.get("score")).isEqualTo(80);
        assertThat(kept.get("engine")).isEqualTo("LLM");
        assertThat(kept.get("prompt_version")).isEqualTo("legacy");

        // job 的时间字段已补齐并变为非空。
        assertThat(jdbc.queryForObject("SELECT create_time IS NOT NULL AND update_time IS NOT NULL FROM job WHERE id = 1", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_name = 'job' AND column_name = 'create_time'",
                String.class)).isEqualTo("NO");
    }

    @Test
    void legacyDuplicatesWithoutCompletedResultShouldKeepTheNewest() {
        DataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");

        flyway(dataSource, "17").migrate();
        jdbc.update("INSERT INTO users (id, user_account, user_password) VALUES (1, 'alice', 'hash')");
        jdbc.update("INSERT INTO job (id, user_id, company_name, job_title) VALUES (1, 1, 'Google', 'SWE')");
        jdbc.update("INSERT INTO resume (id, user_id, resume_name, latest_version_number) VALUES (1, 1, 'A', 1)");
        jdbc.update("INSERT INTO resume_version (id, resume_id, version_number, content_json) VALUES (10, 1, 1, '{}')");
        jdbc.update("INSERT INTO match_record (id, resume_id, job_id) VALUES (1, 1, 1), (2, 1, 1)");

        flyway(dataSource, "latest").migrate();

        Map<String, Object> kept = jdbc.queryForMap("SELECT id, status, last_error_code FROM match_record");
        assertThat(kept.get("id")).isEqualTo(2L);
        assertThat(kept.get("status")).isEqualTo("DEAD");
        assertThat(kept.get("last_error_code")).isEqualTo("LEGACY_INCOMPLETE");
    }

    private static Flyway flyway(DataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .target(target)
                .load();
    }
}
