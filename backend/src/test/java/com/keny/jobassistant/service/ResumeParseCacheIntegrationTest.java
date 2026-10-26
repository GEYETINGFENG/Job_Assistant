package com.keny.jobassistant.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.exception.ResumeParseInProgressException;
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
@Tag("integration")
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
        // 24h ± 10% 抖动。
        assertThat(redis.getExpire(cache.keyFor(file))).isBetween(77700L, 95040L);
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
                tasks.add(() -> {
                    cache.find("jobassistant:resume:parse:absent:test-model:v1");
                    return null;
                });
            }
            for (Future<Void> future : executor.invokeAll(tasks)) future.get();
        } finally {
            executor.shutdownNow();
        }
        assertThat(cache.stats().miss()).isEqualTo(100);
        assertThat(cache.stats().hit()).isZero();
    }

    /** 防雪崩：同一时刻写入的一批缓存，过期时间被打散在 ±10% 范围内，而不是同一秒集体失效。 */
    @Test
    void ttlJitterShouldSpreadExpiryOfABatch() throws Exception {
        var result = parser(cache, "test-model");
        expectLlm(1);
        ResumeParseResult parsed = result.parseResume(file);
        var expiries = new java.util.HashSet<Long>();
        for (int i = 0; i < 50; i++) {
            String key = "jobassistant:resume:parse:batch" + i + ":test-model:v1";
            cache.put(key, new ResumeParseResult(parsed.parsedJson(), parsed.mediaType(), parsed.extension()));
            long expire = redis.getExpire(key);
            assertThat(expire).isBetween(77700L, 95040L);
            expiries.add(expire / 600);
        }
        // 按 10 分钟分桶，50 个 key 至少落在 10 个不同的桶里。
        assertThat(expiries).hasSizeGreaterThanOrEqualTo(10);
    }

    /** 防穿透：同一份内容连续 3 次解析失败后，10 分钟内直接拒绝，不再调用模型。 */
    @Test
    void repeatedInvalidOutputShouldStopCallingTheModel() throws Exception {
        expectInvalidLlm(3);
        var parser = parser(cache, "test-model");
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> parser.parseResume(file))
                    .isInstanceOfSatisfying(BusinessException.class,
                            exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode()));
        }

        // 第 4 次：不再请求模型（MockRestServiceServer 只准备了 3 个响应），且不可重试。
        assertThatThrownBy(() -> parser.parseResume(file))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.RESUME_UNPARSEABLE.getCode()));
        verify(limiter, times(3)).acquire();
        assertThat(redis.getExpire(cache.keyFor(file) + ":failures")).isBetween(1L, 600L);
    }

    @Test
    void successfulParseShouldClearEarlierFailures() throws Exception {
        expectInvalidLlm(1);
        expectLlm(1);
        var parser = parser(cache, "test-model");
        assertThatThrownBy(() -> parser.parseResume(file)).isInstanceOf(BusinessException.class);
        assertThat(redis.hasKey(cache.keyFor(file) + ":failures")).isTrue();

        parser.parseResume(file);

        assertThat(redis.hasKey(cache.keyFor(file) + ":failures")).isFalse();
    }

    /** 防击穿：另一个任务正在解析同一份内容时，延后重试而不是再调一次模型；锁释放后正常解析。 */
    @Test
    void parseInProgressShouldDeferWithoutCallingTheModel() throws Exception {
        var parser = parser(cache, "test-model");
        String key = cache.keyFor(file);
        try (var heldByOtherWorker = cache.tryLock(key)) {
            assertThat(heldByOtherWorker.acquired()).isTrue();
            assertThatThrownBy(() -> parser.parseResume(file)).isInstanceOf(ResumeParseInProgressException.class);
            verifyNoInteractions(limiter);
        }

        expectLlm(1);
        parser.parseResume(file);
        assertThat(redis.hasKey(key + ":lock")).isFalse();
    }

    /** 拿到锁后二次检查：find 时还是 miss，但拿到锁时持有锁的任务刚好写完缓存，直接复用，不再调用模型。 */
    @Test
    void resultCachedWhileWaitingForTheLockShouldBeReused() throws Exception {
        expectLlm(1);
        ResumeParseResult first = parser(cache, "test-model").parseResume(file);
        // find 永远报告 miss，模拟"查缓存时对方还没写完"。
        var missOnFind = new ResumeParseCacheService(redis, mapper, "test-model", "v1", Duration.ofHours(24)) {
            @Override
            public java.util.Optional<ResumeParseResult> find(String key) {
                return java.util.Optional.empty();
            }
        };

        ResumeParseResult second = parser(missOnFind, "test-model").parseResume(file);

        // MockRestServiceServer 只准备了 1 个响应：第二次没有调用模型，也没有申请限流令牌。
        assertThat(second.parsedJson()).isEqualTo(first.parsedJson());
        assertThat(second.promptTokens()).isNull();
        verify(limiter, times(1)).acquire();
    }

    /** 锁过期后被别人重新获取时，旧持有者释放锁不能删掉别人的锁。 */
    @Test
    void lockShouldOnlyBeReleasedByItsOwner() {
        String key = "jobassistant:resume:parse:owner:test-model:v1";
        var first = cache.tryLock(key);
        redis.delete(key + ":lock");
        var second = cache.tryLock(key);
        assertThat(second.acquired()).isTrue();

        first.close();

        assertThat(redis.hasKey(key + ":lock")).isTrue();
        second.close();
        assertThat(redis.hasKey(key + ":lock")).isFalse();
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
        return new BailianResumeParserServiceImpl(client, mapper, extractor, targetCache, limiter, new SimpleMeterRegistry(), model, false, 30000, 4000);
    }

    private void expectLlm(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            server.expect(requestTo("https://llm.test/chat/completions"))
                    .andRespond(withSuccess(response(ResumeParseFixtures.modelOutput("Alice", "Java")), MediaType.APPLICATION_JSON));
        }
    }

    /** 模型有响应，但缺字段，过不了 ResumeParseValidator。 */
    private void expectInvalidLlm(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            server.expect(requestTo("https://llm.test/chat/completions"))
                    .andRespond(withSuccess(response("{\"name\":\"Alice\"}"), MediaType.APPLICATION_JSON));
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
