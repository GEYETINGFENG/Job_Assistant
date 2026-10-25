package com.keny.jobassistant.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keny.jobassistant.exception.MatchAnalysisException;
import com.keny.jobassistant.service.LlmRateLimiter;
import com.keny.jobassistant.service.match.BailianMatchAnalyzer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * 阿里云百炼 text-embedding（OpenAI 兼容 /embeddings 接口）。
 * 每批最多 10 条；每批消耗一个 LLM 令牌（与匹配分析共用全局限流）；错误分类与匹配分析一致。
 */
public class DashScopeEmbeddingClient implements EmbeddingClient {

    static final int MAX_BATCH_SIZE = 10;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final LlmRateLimiter rateLimiter;
    private final MeterRegistry meterRegistry;
    private final String model;
    private final int dimensions;

    public DashScopeEmbeddingClient(RestClient restClient, ObjectMapper objectMapper, LlmRateLimiter rateLimiter,
                                    MeterRegistry meterRegistry, String model, int dimensions) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.rateLimiter = rateLimiter;
        this.meterRegistry = meterRegistry;
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += MAX_BATCH_SIZE) {
            vectors.addAll(embedBatch(texts.subList(start, Math.min(texts.size(), start + MAX_BATCH_SIZE))));
        }
        return vectors;
    }

    private List<float[]> embedBatch(List<String> batch) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("dimensions", dimensions);
        body.put("encoding_format", "float");
        batch.forEach(body.putArray("input")::add);

        rateLimiter.acquire();
        JsonNode response;
        try {
            response = restClient.post().uri("/embeddings").body(body).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException exception) {
            throw BailianMatchAnalyzer.classifyHttpError(exception.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            throw new MatchAnalysisException(BailianMatchAnalyzer.LLM_UNAVAILABLE, "Embedding service is temporarily unreachable", true);
        } catch (RestClientException exception) {
            throw new MatchAnalysisException(BailianMatchAnalyzer.LLM_UNAVAILABLE, "Embedding request failed", true);
        }

        JsonNode data = response == null ? null : response.path("data");
        if (data == null || !data.isArray() || data.size() != batch.size()) {
            throw new MatchAnalysisException("EMBEDDING_INVALID_OUTPUT", "Embedding service returned an unexpected result", true);
        }
        float[][] ordered = new float[batch.size()][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            JsonNode values = item.path("embedding");
            if (index < 0 || index >= batch.size() || !values.isArray() || values.size() != dimensions) {
                throw new MatchAnalysisException("EMBEDDING_INVALID_OUTPUT", "Embedding has an unexpected shape", true);
            }
            float[] vector = new float[dimensions];
            for (int i = 0; i < dimensions; i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            ordered[index] = normalize(vector);
        }
        JsonNode tokens = response.path("usage").path("total_tokens");
        if (tokens.isInt()) {
            meterRegistry.counter("llm.tokens", "feature", "embedding", "type", "prompt").increment(tokens.asInt());
        }
        return List.of(ordered);
    }

    private static float[] normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) norm += value * value;
        if (norm == 0) return vector;
        float inverse = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) vector[i] *= inverse;
        return vector;
    }
}
