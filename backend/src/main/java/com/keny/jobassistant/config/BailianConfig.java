package com.keny.jobassistant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * 阿里云百炼客户端配置。
 */
@Configuration
@ConditionalOnProperty(
        prefix = "app.resume.ai",
        name = "enabled",
        havingValue = "true"
)
//只有app.resume.ai.enabled=true才加载这个文件
public class BailianConfig {

    /**
     * 创建阿里云百炼 API 客户端。
     */
    @Bean("bailianRestClient")
    public RestClient bailianRestClient(
            @Value("${app.resume.ai.api-key:}") String apiKey,
            @Value("${app.resume.ai.base-url}") String baseUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY must be configured when resume AI parsing is enabled");
        }

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("Bailian base URL must be configured when resume AI parsing is enabled");
        }

        return RestClient.builder()
                .baseUrl(baseUrl) //设置基础地址
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}