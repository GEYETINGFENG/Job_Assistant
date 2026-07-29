package com.keny.jobassistant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 测试环境 PostgreSQL 容器配置。
 *
 * 测试启动时：
 * 1. 创建一个全新的 PostgreSQL 数据库
 * 2. 自动向 Spring Boot 提供数据库连接信息
 * 3. 执行项目中的全部 Flyway 迁移
 * 4. 测试结束后关闭并删除容器
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * 创建测试专用 PostgreSQL 容器。
     */
    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgreSQLContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("jobassistant_test")
                .withUsername("test")
                .withPassword("test");
    }
}