package com.keny.jobassistant.service;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 每个用户每天可以发起的匹配次数（LLM 按量付费，防止单个用户把账单刷爆）。
 * 计数器放在 Redis：多个实例共享同一份额度，放在应用内存里每个实例各算各的会成倍放大上限。
 */
@Slf4j
@Service
public class MatchQuotaService {

    private static final String KEY_PREFIX = "jobassistant:match:quota:";

    /**
     * 原子地加一并在第一次使用时设置过期时间；超过上限时减回去，保证计数只统计真正放行的请求。
     */
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local used = redis.call('INCR', KEYS[1])
            if used == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            if used > tonumber(ARGV[1]) then
                redis.call('DECR', KEYS[1])
                return -1
            end
            return used
            """, Long.class);

    private final StringRedisTemplate redis;
    private final int dailyLimit;
    private final Clock clock;

    @Autowired
    public MatchQuotaService(StringRedisTemplate redis,
                             @Value("${app.match.daily-quota-per-user:20}") int dailyLimit) {
        this(redis, dailyLimit, Clock.systemDefaultZone());
    }

    MatchQuotaService(StringRedisTemplate redis, int dailyLimit, Clock clock) {
        if (dailyLimit < 1) {
            throw new IllegalArgumentException("Daily match quota must be positive");
        }
        this.redis = redis;
        this.dailyLimit = dailyLimit;
        this.clock = clock;
    }

    /**
     * 消耗一次额度；已用完时抛出 QUOTA_EXCEEDED（429）。
     * Redis 不可用时拒绝请求（失败关闭），不能在无法计数时放任 LLM 调用。
     */
    public void consume(Long userId) {
        ZonedDateTime now = ZonedDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        String key = KEY_PREFIX + userId + ":" + today.format(DateTimeFormatter.BASIC_ISO_DATE);
        // 过期到次日零点后一小时，跨天的请求不会误读到前一天的计数。
        long ttlMillis = Duration.between(now, today.plusDays(1).atStartOfDay(now.getZone()).plusHours(1)).toMillis();

        Long used;
        try {
            used = redis.execute(CONSUME_SCRIPT, List.of(key), Integer.toString(dailyLimit), Long.toString(ttlMillis));
        } catch (DataAccessException exception) {
            log.error("匹配额度计数不可用，exceptionType={}", exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Match quota service is unavailable");
        }
        if (used == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Invalid match quota response");
        }
        if (used < 0) {
            throw new BusinessException(ErrorCode.QUOTA_EXCEEDED,
                    "Daily match limit of " + dailyLimit + " reached, please try again tomorrow");
        }
    }
}
