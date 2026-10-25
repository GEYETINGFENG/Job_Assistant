package com.keny.jobassistant.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keny.jobassistant.exception.MatchAnalysisException;
import com.keny.jobassistant.service.LlmRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DashScopeEmbeddingClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String URL = "http://llm.test/embeddings";
    private static final int DIMENSIONS = 4;

    private MockRestServiceServer server;
    private LlmRateLimiter rateLimiter;
    private DashScopeEmbeddingClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://llm.test");
        server = MockRestServiceServer.bindTo(builder).build();
        rateLimiter = mock(LlmRateLimiter.class);
        client = new DashScopeEmbeddingClient(builder.build(), MAPPER, rateLimiter, new SimpleMeterRegistry(), "text-embedding-v4", DIMENSIONS);
    }

    /** 返回顺序故意打乱：结果必须按 index 对应回输入。 */
    private static String response(int count) {
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode data = body.putArray("data");
        for (int i = count - 1; i >= 0; i--) {
            ObjectNode item = data.addObject().put("index", i);
            ArrayNode embedding = item.putArray("embedding");
            for (int d = 0; d < DIMENSIONS; d++) embedding.add(d == 0 ? 3.0 + i : 4.0);
        }
        body.putObject("usage").put("total_tokens", 12);
        return body.toString();
    }

    @Test
    void shouldBatchRequestsAndKeepInputOrder() {
        server.expect(requestTo(URL)).andExpect(jsonPath("$.input.length()").value(10))
                .andExpect(jsonPath("$.dimensions").value(DIMENSIONS))
                .andRespond(withSuccess(response(10), MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andExpect(jsonPath("$.input.length()").value(2))
                .andRespond(withSuccess(response(2), MediaType.APPLICATION_JSON));

        List<String> texts = IntStream.range(0, 12).mapToObj(i -> "text " + i).toList();
        List<float[]> vectors = client.embed(texts);

        assertThat(vectors).hasSize(12);
        // 第 0 条：(3, 4, 4, 4) 归一化后第一维是 3 / sqrt(57)。
        assertThat(vectors.get(0)[0]).isCloseTo((float) (3 / Math.sqrt(57)), within(1e-5f));
        assertThat(HashingEmbeddingClient.cosine(vectors.get(0), vectors.get(0))).isCloseTo(1.0, within(1e-5));
        verify(rateLimiter, times(2)).acquire();
        server.verify();
    }

    @Test
    void rejectedKeyShouldNotBeRetryable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.embed(List.of("text")))
                .isInstanceOfSatisfying(MatchAnalysisException.class, exception -> assertThat(exception.isRetryable()).isFalse());
    }

    @Test
    void serverErrorShouldBeRetryable() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.embed(List.of("text")))
                .isInstanceOfSatisfying(MatchAnalysisException.class, exception -> assertThat(exception.isRetryable()).isTrue());
    }

    @Test
    void wrongDimensionsShouldBeRejected() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"data\": [{\"index\": 0, \"embedding\": [1.0, 2.0]}]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embed(List.of("text"))).isInstanceOf(MatchAnalysisException.class);
    }
}
