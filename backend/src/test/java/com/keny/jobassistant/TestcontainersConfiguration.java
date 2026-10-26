package com.keny.jobassistant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 测试环境 PostgreSQL 和 Redis 容器配置。
 *
 * 测试启动时：
 * 1. 创建一个全新的 PostgreSQL 数据库
 * 2. 自动向 Spring Boot 提供数据库连接信息
 * 3. 执行项目中的全部 Flyway 迁移
 * 4. 启动独立的 Redis，测试绝不会连到开发机上的 Redis
 * 5. 测试结束后关闭并删除容器
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    /**
     * 创建测试专用 PostgreSQL 容器。
     */
    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgreSQLContainer() {
        return new PostgreSQLContainer<>(TestDatabaseImages.POSTGRES_WITH_PGVECTOR)
                .withDatabaseName("jobassistant_test")
                .withUsername("test")
                .withPassword("test");
    }

    /**
     * 创建测试专用 Redis 容器（Refresh Token 宽限缓存、匹配额度、LLM 限流都依赖 Redis）。
     */
    @Bean
    @ServiceConnection(name = "redis")
    public GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    }
}
