package com.keny.jobassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.document.ResumeDocumentContent;
import com.keny.jobassistant.service.impl.BailianResumeParserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 故障注入验证：Redis 失败不能意外放行模型或推翻已经成功的解析。 */
class ResumeParseCacheFailureTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    private final TikaResumeDocumentExtractor extractor = mock(TikaResumeDocumentExtractor.class);
    private final MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[]{1, 2, 3});
    private ResumeParseCacheService cache;
    private BailianResumeParserServiceImpl parser;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hashes);
        when(extractor.extract(file)).thenReturn(new ResumeDocumentContent("application/pdf", ".pdf", "Alice"));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        cache = new ResumeParseCacheService(redis, mapper, "test-model", "v1", Duration.ofHours(24));
        RestClient.Builder builder = RestClient.builder().baseUrl("https://llm.test");
        server = MockRestServiceServer.bindTo(builder).build();
        parser = new BailianResumeParserServiceImpl(builder.build(), mapper, extractor, cache, "test-model", false, 30000, 4000);
    }

    @Test
    void failedReadShouldNotCallModelOrCountMiss() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("test failure"));
        assertThatThrownBy(() -> parser.parseResume(file)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(hashes);
        server.verify();
    }

    @Test
    void failedWriteShouldKeepSuccessfulModelResult() {
        expectModel();
        doThrow(new RedisConnectionFailureException("test failure")).when(values).set(anyString(), anyString(), any(Duration.class));
        assertThat(parser.parseResume(file).parsedJson().path("name").asText()).isEqualTo("Alice");
        verify(hashes).increment(anyString(), eq("miss"), eq(1L));
        server.verify();
    }

    @Test
    void failedCounterShouldNotFailParsing() {
        expectModel();
        when(hashes.increment(anyString(), eq("miss"), eq(1L))).thenThrow(new RedisConnectionFailureException("test failure"));
        assertThat(parser.parseResume(file).parsedJson().path("name").asText()).isEqualTo("Alice");
        verify(values).set(anyString(), anyString(), any(Duration.class));
        server.verify();
    }

    @Test
    void failedStatisticsReadShouldNotReturnFalseZeros() {
        when(hashes.multiGet(anyString(), anyCollection())).thenThrow(new RedisConnectionFailureException("test failure"));
        assertThatThrownBy(cache::stats).isInstanceOf(BusinessException.class);
    }

    private void expectModel() {
        server.expect(requestTo("https://llm.test/chat/completions")).andRespond(withSuccess(
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"name\\\":\\\"Alice\\\"}\"}}]}", MediaType.APPLICATION_JSON));
    }
}
