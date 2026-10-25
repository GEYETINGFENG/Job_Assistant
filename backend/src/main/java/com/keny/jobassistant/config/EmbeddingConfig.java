package com.keny.jobassistant.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.service.LlmRateLimiter;
import com.keny.jobassistant.service.rag.DashScopeEmbeddingClient;
import com.keny.jobassistant.service.rag.EmbeddingClient;
import com.keny.jobassistant.service.rag.HashingEmbeddingClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * 选择 embedding 实现：hash（默认，本地确定性、免费）或 dashscope（语义 embedding，需要开启 AI）。
 * 两者的维度相同，但向量不在同一语义空间，resume_chunk 按 embedding_model 区分。
 */
@Configuration
public class EmbeddingConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.rag.embedding", name = "provider", havingValue = "hash", matchIfMissing = true)
    public EmbeddingClient hashingEmbeddingClient() {
        return new HashingEmbeddingClient();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.rag.embedding", name = "provider", havingValue = "dashscope")
    public EmbeddingClient dashScopeEmbeddingClient(@Qualifier("bailianRestClient") RestClient bailianRestClient,
                                                    ObjectMapper objectMapper,
                                                    LlmRateLimiter rateLimiter,
                                                    MeterRegistry meterRegistry,
                                                    @Value("${app.rag.embedding.model:text-embedding-v4}") String model) {
        return new DashScopeEmbeddingClient(bailianRestClient, objectMapper, rateLimiter, meterRegistry, model,
                HashingEmbeddingClient.DIMENSIONS);
    }
}
