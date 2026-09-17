package com.keny.jobassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.document.ResumeParseResult;
import com.keny.jobassistant.service.impl.BailianResumeParserServiceImpl;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** 使用真实 Redis、真实 Tika 和模拟 HTTP 响应，不访问百炼付费接口。 */
@Testcontainers
class ResumeParseCacheIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private ResumeParseCacheService cache;
    private MockRestServiceServer server;
    private RestClient client;
    private MockMultipartFile file;
    private final LlmRateLimiter limiter = mock(LlmRateLimiter.class);

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() throws Exception {
        // 仅清理本测试独享的临时容器，绝不连接开发 Redis。
        try (var connection = connectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        cache = cache("test-model", "v1", Duration.ofHours(24));
        RestClient.Builder builder = RestClient.builder().baseUrl("https://llm.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        file = pdf("resume.pdf", "Alice - Java developer");
    }

    @AfterEach
    void verifyHttpRequests() {
        server.verify();
    }

    @Test
    void secondParseShouldHitCacheWithoutAnotherLlmRequest() throws Exception {
        expectLlm(1);
        var parser = parser(cache, "test-model");
        ResumeParseResult first = parser.parseResume(file);
        ResumeParseResult second = parser.parseResume(file);
        assertThat(second).isEqualTo(first);
        verify(limiter, times(1)).acquire();
        assertThat(cache.stats().hit()).isEqualTo(1);
        assertThat(cache.stats().miss()).isEqualTo(1);
        assertThat(cache.stats().hitRate()).isEqualTo(0.5);
        assertThat(redis.getExpire(cache.keyFor(file))).isBetween(86300L, 86400L);
        assertThat(redis.getExpire("jobassistant:resume:parse:stats:test-model:v1")).isEqualTo(-1);
    }

    @Test
    void sameBytesWithAnotherFilenameShouldHitButInvalidExtensionShouldFail() throws Exception {
        expectLlm(1);
        var parser = parser(cache, "test-model");
        var first = parser.parseResume(file);
        assertThat(parser.parseResume(new MockMultipartFile("file", "renamed.pdf", "application/pdf", file.getBytes()))).isEqualTo(first);
        assertThatThrownBy(() -> parser.parseResume(new MockMultipartFile("file", "fake.docx", "application/pdf", file.getBytes())))
                .isInstanceOf(BusinessException.class);
        assertThat(cache.stats().total()).isEqualTo(2);
    }

    @Test
    void contentModelAndPromptChangesShouldMiss() throws Exception {
        expectLlm(4);
        var parser = parser(cache, "test-model");
        parser.parseResume(file);
        parser.parseResume(pdf("resume.pdf", "Bob - Python developer"));
        var nextModel = cache("next-model", "v1", Duration.ofHours(24));
        parser(nextModel, "next-model").parseResume(file);
        var nextPrompt = cache("test-model", "v2", Duration.ofHours(24));
        parser(nextPrompt, "test-model").parseResume(file);
        assertThat(cache.stats().miss()).isEqualTo(2);
        assertThat(nextModel.stats().miss()).isEqualTo(1);
        assertThat(nextPrompt.stats().miss()).isEqualTo(1);
    }

    @Test
    void expiredEntryShouldCallLlmAgain() throws Exception {
        expectLlm(2);
        var shortCache = cache("test-model", "v1", Duration.ofMillis(100));
        var parser = parser(shortCache, "test-model");
        parser.parseResume(file);
        await().atMost(Duration.ofSeconds(3)).until(() -> Boolean.FALSE.equals(redis.hasKey(shortCache.keyFor(file))));
        parser.parseResume(file);
        assertThat(shortCache.stats().miss()).isEqualTo(2);
    }

    @Test
    void corruptOrIncompleteEntryShouldBeReplacedAndCountedOnce() throws Exception {
        expectLlm(2);
        var parser = parser(cache, "test-model");
        for (String corrupt : new String[]{"not-json", "{}"}) {
            redis.opsForValue().set(cache.keyFor(file), corrupt);
            parser.parseResume(file);
            assertThat(mapper.readTree(redis.opsForValue().get(cache.keyFor(file))).path("parsedJson").path("name").asText()).isEqualTo("Alice");
        }
        assertThat(cache.stats().miss()).isEqualTo(2);
        assertThat(cache.stats().hit()).isZero();
    }

    @Test
    void invalidModelResponseShouldNeverBeCached() throws Exception {
        for (String invalid : new String[]{"not-json", "null"}) {
            server.expect(requestTo("https://llm.test/chat/completions")).andRespond(withSuccess(response(invalid), MediaType.APPLICATION_JSON));
        }
        var parser = parser(cache, "test-model");
        assertThatThrownBy(() -> parser.parseResume(file)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> parser.parseResume(file)).isInstanceOf(BusinessException.class);
        assertThat(redis.hasKey(cache.keyFor(file))).isFalse();
        assertThat(cache.stats().miss()).isEqualTo(2);
    }

    @Test
    void concurrentMissCountersShouldNotLoseUpdates() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 100; i++) {
                tasks.add(() -> { cache.find("jobassistant:resume:parse:absent:test-model:v1"); return null; });
            }
            for (Future<Void> future : executor.invokeAll(tasks)) future.get();
        } finally {
            executor.shutdownNow();
        }
        assertThat(cache.stats().miss()).isEqualTo(100);
        assertThat(cache.stats().hit()).isZero();
    }

    @Test
    void emptyStatsShouldBeZero() {
        assertThat(cache.stats().total()).isZero();
        assertThat(cache.stats().hitRate()).isZero();
    }

    private ResumeParseCacheService cache(String model, String prompt, Duration ttl) {
        return new ResumeParseCacheService(redis, mapper, model, prompt, ttl);
    }

    private BailianResumeParserServiceImpl parser(ResumeParseCacheService targetCache, String model) {
        var extractor = new TikaResumeDocumentExtractor(100000, 2000, 20971520, 52428800, 100, 1048576);
        return new BailianResumeParserServiceImpl(client, mapper, extractor, targetCache, limiter, model, false, 30000, 4000);
    }

    private void expectLlm(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            server.expect(requestTo("https://llm.test/chat/completions"))
                    .andRespond(withSuccess(response("{\"name\":\"Alice\",\"skills\":[\"Java\"]}"), MediaType.APPLICATION_JSON));
        }
    }

    private String response(String content) throws Exception {
        var root = mapper.createObjectNode();
        root.putArray("choices").addObject().putObject("message").put("content", content);
        return mapper.writeValueAsString(root);
    }

    private MockMultipartFile pdf(String name, String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            document.save(bytes);
            return new MockMultipartFile("file", name, "application/pdf", bytes.toByteArray());
        }
    }
}
