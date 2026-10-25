package com.keny.jobassistant;

import org.testcontainers.utility.DockerImageName;

/**
 * 测试用数据库镜像。执行完整 Flyway 迁移的测试必须使用带 pgvector 扩展的镜像（V19 创建 vector 扩展）。
 */
public final class TestDatabaseImages {

    public static final DockerImageName POSTGRES_WITH_PGVECTOR =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    private TestDatabaseImages() {
    }
}
