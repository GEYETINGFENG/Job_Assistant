package com.keny.jobassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.exception.LlmRateLimitedException;
import com.keny.jobassistant.model.document.ResumeDocumentContent;
import com.keny.jobassistant.service.impl.BailianResumeParserServiceImpl;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 在独享 Redis 容器内验证实际 Lua、独立客户端和模型请求的组合行为。 */
@Testcontainers
class LlmRateLimiterIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static LettuceConnectionFactory factory;
    private static LettuceConnectionFactory otherFactory;
    private static StringRedisTemplate redis;
    private static StringRedisTemplate otherRedis;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        otherFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        otherFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        otherRedis = new StringRedisTemplate(otherFactory);
    }

    @AfterAll
    static void close() {
        if (factory != null) factory.destroy();
        if (otherFactory != null) otherFactory.destroy();
    }

    @BeforeEach
    void clearTestContainer() {
        try (var connection = factory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void defaultBucketShouldAllowThreeAndReturnWaitWithSafeTtl() {
        LlmRateLimiter limiter = new LlmRateLimiter(redis, 3, 0.1);
        for (int i = 0; i < 3; i++) limiter.acquire();
        var denied = catchThrowableOfType(limiter::acquire, LlmRateLimitedException.class);
        assertThat(denied).isNotNull();
        assertThat(denied.getRetryAfterMillis()).isBetween(1L, 10000L);
        assertThat(redis.getExpire(LlmRateLimiter.BUCKET_KEY, TimeUnit.MILLISECONDS)).isBetween(30001L, 61000L);
    }

    @Test
    void independentClientsShouldShareAtomicCapacityUnderConcurrency() throws Exception {
        // 补充周期足够长，使测试期间无法生成新令牌。
        LlmRateLimiter first = new LlmRateLimiter(redis, 3, 0.0001);
        LlmRateLimiter second = new LlmRateLimiter(otherRedis, 3, 0.0001);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                LlmRateLimiter selected = i % 2 == 0 ? first : second;
                tasks.add(() -> allowed(selected));
            }
            int successes = 0;
            for (Future<Boolean> result : executor.invokeAll(tasks)) if (result.get()) successes++;
            assertThat(successes).isEqualTo(3);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fractionalRefillShouldAccumulateEvenAcrossDeniedRequests() {
        LlmRateLimiter limiter = new LlmRateLimiter(redis, 1, 2);
        limiter.acquire();
        assertThat(allowed(limiter)).isFalse();
        await().pollInterval(Duration.ofMillis(20)).atMost(Duration.ofSeconds(3)).until(() -> allowed(limiter));
        assertThat(allowed(limiter)).isFalse();
    }

    @Test
    void longIdleShouldCapTokensAndExpiredBucketMayStartFull() {
        LlmRateLimiter limiter = new LlmRateLimiter(redis, 3, 0.1);
        // 使用 Redis 自己的时间制造长时间闲置，避免依赖应用时钟或等待 30 秒。
        redis.execute(new DefaultRedisScript<>("local t=redis.call('TIME'); redis.call('HSET', KEYS[1], 'tokens', '0', "
                + "'last_refill_ms', t[1]*1000+math.floor(t[2]/1000)-60000); return 1", Long.class), List.of(LlmRateLimiter.BUCKET_KEY));
        for (int i = 0; i < 3; i++) limiter.acquire();
        assertThat(allowed(limiter)).isFalse();
        redis.delete(LlmRateLimiter.BUCKET_KEY);
        LlmRateLimiter fast = new LlmRateLimiter(redis, 1, 10);
        fast.acquire();
        await().atMost(Duration.ofSeconds(4)).until(() -> Boolean.FALSE.equals(redis.hasKey(LlmRateLimiter.BUCKET_KEY)));
        assertThat(allowed(fast)).isTrue();
    }

    @Test
    void corruptBucketShouldFailClosed() {
        redis.opsForHash().put(LlmRateLimiter.BUCKET_KEY, "tokens", "corrupt");
        assertThatThrownBy(new LlmRateLimiter(redis, 3, 0.1)::acquire).isInstanceOf(BusinessException.class);
        assertThat(redis.opsForHash().get(LlmRateLimiter.BUCKET_KEY, "tokens")).isEqualTo("corrupt");
    }

    @Test
    void failedHttpRequestShouldConsumeTokenAndNextRequestShouldNotReachLlm() {
        var fixture = parserFixture();
        fixture.server.expect(requestTo("https://llm.test/chat/completions")).andRespond(withServerError());
        assertThatThrownBy(() -> fixture.parser.parseResume(fixture.file)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fixture.parser.parseResume(fixture.file)).isInstanceOf(LlmRateLimitedException.class);
        fixture.server.verify();
    }

    @Test
    void cachedResultShouldWorkEvenWithEmptyBucket() {
        var fixture = parserFixture();
        fixture.server.expect(requestTo("https://llm.test/chat/completions")).andRespond(withSuccess(
                "{\"choices\":[{\"message\":{\"content\":\"{\\\"name\\\":\\\"Alice\\\"}\"}}]}", MediaType.APPLICATION_JSON));
        var first = fixture.parser.parseResume(fixture.file);
        assertThat(allowed(new LlmRateLimiter(redis, 1, 0.0001))).isFalse();
        assertThat(fixture.parser.parseResume(fixture.file)).isEqualTo(first);
        fixture.server.verify();
    }

    private Fixture parserFixture() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var cache = new ResumeParseCacheService(redis, mapper, "test-model", "v1", Duration.ofHours(24));
        var extractor = mock(TikaResumeDocumentExtractor.class);
        var file = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[]{1, 2, 3});
        when(extractor.extract(file)).thenReturn(new ResumeDocumentContent("application/pdf", ".pdf", "Alice"));
        RestClient.Builder builder = RestClient.builder().baseUrl("https://llm.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var parser = new BailianResumeParserServiceImpl(builder.build(), mapper, extractor, cache,
                new LlmRateLimiter(redis, 1, 0.0001), "test-model", false, 30000, 4000);
        return new Fixture(parser, server, file);
    }

    private boolean allowed(LlmRateLimiter limiter) {
        try {
            limiter.acquire();
            return true;
        } catch (LlmRateLimitedException exception) {
            return false;
        }
    }

    private record Fixture(BailianResumeParserServiceImpl parser, MockRestServiceServer server, MockMultipartFile file) {
    }
}
