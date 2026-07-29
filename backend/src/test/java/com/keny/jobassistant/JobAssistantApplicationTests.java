package com.keny.jobassistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring Boot 应用上下文测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class JobAssistantApplicationTests {

    /**
     * 验证 Spring 容器、数据库、Flyway、JPA 和安全配置能够正常启动。
     */
    @Test
    void contextLoads() {
    }
}