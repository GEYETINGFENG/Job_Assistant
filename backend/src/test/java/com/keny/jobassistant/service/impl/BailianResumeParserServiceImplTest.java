package com.keny.jobassistant.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.document.ResumeDocumentContent;
import com.keny.jobassistant.model.document.ResumeParseResult;
import com.keny.jobassistant.service.LlmRateLimiter;
import com.keny.jobassistant.service.ResumeParseCacheService;
import com.keny.jobassistant.service.TikaResumeDocumentExtractor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BailianResumeParserServiceImplTest {

    private static final String URL = "http://llm.test/chat/completions";
    private static final String CACHE_KEY = "jobassistant:resume:parse:test";
    private static final String TEXT = "Alex Chen\nalex.chen@example.com\nSkills: Java, Kafka";
    private static final MockMultipartFile FILE = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[]{1, 2, 3});

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new Jdk8Module());
    private MockRestServiceServer server;
    private ResumeParseCacheService cacheService;
    private SimpleMeterRegistry meterRegistry;
    private BailianResumeParserServiceImpl parser;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://llm.test");
        server = MockRestServiceServer.bindTo(builder).build();
        TikaResumeDocumentExtractor extractor = mock(TikaResumeDocumentExtractor.class);
        when(extractor.extract(any())).thenReturn(new ResumeDocumentContent("application/pdf", ".pdf", TEXT));
        cacheService = mock(ResumeParseCacheService.class);
        when(cacheService.keyFor(any())).thenReturn(CACHE_KEY);
        when(cacheService.find(CACHE_KEY)).thenReturn(Optional.empty());
        when(cacheService.tryLock(CACHE_KEY)).thenReturn(new ResumeParseCacheService.ParseLock(true, () -> { }));
        meterRegistry = new SimpleMeterRegistry();
        parser = new BailianResumeParserServiceImpl(builder.build(), objectMapper, extractor, cacheService,
                mock(LlmRateLimiter.class), meterRegistry, "qwen-test", false, 30000, 4000);
    }

    /** 模拟百炼的 Chat Completions 响应：content 是模型输出的 JSON 字符串。 */
    private String response(String content) throws Exception {
        var body = objectMapper.createObjectNode();
        body.putArray("choices").addObject().putObject("message").put("content", content);
        body.putObject("usage").put("prompt_tokens", 1200).put("completion_tokens", 300);
        return objectMapper.writeValueAsString(body);
    }

    private static String output(String email) {
        return """
                {"name": "Alex Chen", "email": "%s", "phone": "", "location": "", "summary": "",
                 "skills": ["Java", "java", "Kafka"], "education": [], "experience": [], "projects": [],
                 "certificates": [], "languages": []}
                """.formatted(email);
    }

    @Test
    void successShouldValidateAndReportUsage() throws Exception {
        server.expect(requestTo(URL)).andRespond(withSuccess(response(output("attacker@evil.example")), MediaType.APPLICATION_JSON));

        ResumeParseResult result = parser.parseResume(FILE);

        // 校验和规范化：技能去重，原文中不存在的邮箱被清空。
        assertThat(result.parsedJson().path("skills")).extracting(node -> node.asText()).containsExactly("Java", "Kafka");
        assertThat(result.parsedJson().path("email").asText()).isEmpty();
        assertThat(result.parsedJson().path("rawText").asText()).isEqualTo(TEXT);
        assertThat(result.promptTokens()).isEqualTo(1200);
        assertThat(result.completionTokens()).isEqualTo(300);
        assertThat(meterRegistry.counter("llm.tokens", "feature", "resume_parse", "type", "prompt").count()).isEqualTo(1200);
        assertThat(meterRegistry.counter("llm.tokens", "feature", "resume_parse", "type", "completion").count()).isEqualTo(300);

        // 缓存里不带用量：下次命中缓存没有花 token。
        ArgumentCaptor<ResumeParseResult> cached = ArgumentCaptor.forClass(ResumeParseResult.class);
        verify(cacheService).put(eq(CACHE_KEY), cached.capture());
        assertThat(cached.getValue().promptTokens()).isNull();
        server.verify();
    }

    @Test
    void cacheHitShouldNotCallTheModelOrReportUsage() {
        ResumeParseResult stored = new ResumeParseResult(objectMapper.createObjectNode(), "application/pdf", ".pdf", 1200, 300);
        when(cacheService.find(CACHE_KEY)).thenReturn(Optional.of(stored));

        ResumeParseResult result = parser.parseResume(FILE);

        assertThat(result.promptTokens()).isNull();
        assertThat(result.completionTokens()).isNull();
        server.verify();
    }

    /** 结构不对时按临时错误重试；这次调用的 token 已经花掉，仍然要计入。 */
    @Test
    void invalidOutputShouldBeRetryableAndStillCounted() throws Exception {
        server.expect(requestTo(URL)).andRespond(withSuccess(response("{\"name\": \"Alex Chen\"}"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> parser.parseResume(FILE))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode()));
        assertThat(meterRegistry.counter("llm.tokens", "feature", "resume_parse", "type", "prompt").count()).isEqualTo(1200);
        verify(cacheService, never()).put(any(), any());
    }

    /** 401/403/400 重试也不会成功，直接让任务进入 DEAD；429 和 5xx 是临时问题，可以重试。 */
    @ParameterizedTest
    @CsvSource({"FORBIDDEN,AI_SERVICE_REJECTED", "UNAUTHORIZED,AI_SERVICE_REJECTED", "BAD_REQUEST,AI_SERVICE_REJECTED",
            "TOO_MANY_REQUESTS,SYSTEM_ERROR", "SERVICE_UNAVAILABLE,SYSTEM_ERROR"})
    void httpErrorsShouldBeClassified(HttpStatus status, ErrorCode expected) {
        server.expect(requestTo(URL)).andRespond(withStatus(status));

        assertThatThrownBy(() -> parser.parseResume(FILE))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(expected.getCode()));
    }
}
