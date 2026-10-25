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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V17 迁移测试：先迁移到 V16 写入旧格式（status = 0）的申请，
 * 再迁移到最新版本，验证旧数据被转换为 APPLIED 并补上了创建事件。
 */
@Testcontainers
@Tag("integration")
class ApplicationStatusMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(TestDatabaseImages.POSTGRES_WITH_PGVECTOR)
            .withDatabaseName("jobassistant_application_migration_test")
            .withUsername("test")
            .withPassword("test");

    @Test
    void legacyIntegerStatusShouldBecomeAppliedWithCreationEvent() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        flyway(dataSource, "16").migrate();
        jdbcTemplate.update("INSERT INTO users (id, user_account, user_password) VALUES (1, 'alice', 'hash')");
        jdbcTemplate.update("INSERT INTO job (id, user_id, company_name, job_title) VALUES (1, 1, 'Google', 'SWE')");
        jdbcTemplate.update("""
                INSERT INTO application (id, user_id, job_id, status, apply_time)
                VALUES (1, 1, 1, 0, TIMESTAMP '2026-01-01 12:00:00')
                """);

        flyway(dataSource, "latest").migrate();

        Map<String, Object> application = jdbcTemplate.queryForMap(
                "SELECT status, update_time FROM application WHERE id = 1");
        assertThat(application.get("status")).isEqualTo("APPLIED");
        assertThat(application.get("update_time")).isNotNull();

        Map<String, Object> event = jdbcTemplate.queryForMap(
                "SELECT from_status, to_status, operator_user_id, create_time::text AS create_time "
                        + "FROM application_event WHERE application_id = 1");
        assertThat(event.get("from_status")).isNull();
        assertThat(event.get("to_status")).isEqualTo("APPLIED");
        assertThat(event.get("operator_user_id")).isEqualTo(1L);
        assertThat(event.get("create_time")).isEqualTo("2026-01-01 12:00:00");

        // 新插入的申请不传 status 时使用默认值 APPLIED。
        jdbcTemplate.update("INSERT INTO job (id, user_id, company_name, job_title) VALUES (2, 1, 'Meta', 'SWE')");
        jdbcTemplate.update("INSERT INTO application (id, user_id, job_id) VALUES (2, 1, 2)");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM application WHERE id = 2", String.class))
                .isEqualTo("APPLIED");
    }

    private static Flyway flyway(DataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }
}
