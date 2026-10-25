package com.keny.jobassistant.service;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 在真实 Redis 上验证每日额度的 Lua 脚本：上限、并发、按用户和按天隔离、过期时间。 */
@Testcontainers
@Tag("integration")
class MatchQuotaServiceIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final Clock NOON = Clock.fixed(Instant.parse("2026-10-24T16:00:00Z"), ZONE);
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void close() {
        if (factory != null) factory.destroy();
    }

    @BeforeEach
    void flush() {
        try (var connection = factory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void shouldAllowUpToLimitThenReject() {
        MatchQuotaService quota = new MatchQuotaService(redis, 2, NOON);
        quota.consume(1L);
        quota.consume(1L);

        assertThatThrownBy(() -> quota.consume(1L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED.getCode());
                    assertThat(exception.getHttpStatus().value()).isEqualTo(429);
                });
        // 被拒绝的请求不占用计数。
        assertThat(redis.opsForValue().get("jobassistant:match:quota:1:20261024")).isEqualTo("2");
    }

    @Test
    void keyShouldExpireShortlyAfterMidnight() {
        new MatchQuotaService(redis, 5, NOON).consume(1L);

        Long ttlSeconds = redis.getExpire("jobassistant:match:quota:1:20261024", TimeUnit.SECONDS);
        // 纽约时间中午 12 点，到次日 1 点约 13 小时。
        assertThat(ttlSeconds).isBetween(12 * 3600L, 13 * 3600L);
    }

    @Test
    void quotaShouldBeSeparatedByUserAndDay() {
        new MatchQuotaService(redis, 1, NOON).consume(1L);
        new MatchQuotaService(redis, 1, NOON).consume(2L);
        Clock tomorrow = Clock.offset(NOON, java.time.Duration.ofDays(1));

        new MatchQuotaService(redis, 1, tomorrow).consume(1L);
        assertThatThrownBy(() -> new MatchQuotaService(redis, 1, NOON).consume(1L)).isInstanceOf(BusinessException.class);
    }

    /** 20 个并发请求、上限 5：Lua 脚本原子执行，恰好放行 5 个。 */
    @Test
    void concurrentRequestsShouldNeverExceedLimit() throws Exception {
        MatchQuotaService quota = new MatchQuotaService(redis, 5, NOON);
        ExecutorService executor = Executors.newFixedThreadPool(20);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger allowed = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        quota.consume(1L);
                        allowed.incrementAndGet();
                    } catch (BusinessException ignored) {
                        // 超额被拒绝。
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            assertThat(allowed).hasValue(5);
            assertThat(redis.opsForValue().get("jobassistant:match:quota:1:20261024")).isEqualTo("5");
        } finally {
            executor.shutdownNow();
        }
    }
}
