package com.keny.jobassistant.service;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.exception.LlmRateLimitedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/** 每次实际 LLM 请求消耗一个令牌，失败不退还；所有实例必须使用同一 Redis 和相同参数。 */
@Service
@Slf4j
public class LlmRateLimiter {
    public static final String BUCKET_KEY = "jobassistant:llm:bucket:global";
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SCRIPT = bucketScript();
    private final StringRedisTemplate redis;
    private final int capacity;
    private final double refillPerSecond;
    private final long idleTtlMillis;

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<List> bucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/llm-token-bucket.lua"));
        script.setResultType(List.class);
        return script;
    }

    public LlmRateLimiter(StringRedisTemplate redis,
                          @Value("${app.resume.ai.rate-limit.capacity:3}") int capacity,
                          @Value("${app.resume.ai.rate-limit.refill-per-second:0.1}") double refillPerSecond) {
        double fillMillis = Math.ceil(capacity * 1000.0 / refillPerSecond);// 计算补满时间
        if (capacity < 1 || !Double.isFinite(refillPerSecond) || refillPerSecond <= 0
                || !Double.isFinite(fillMillis) || fillMillis > Long.MAX_VALUE / 4.0) {
            throw new IllegalArgumentException("LLM bucket capacity and refill rate must be positive and finite");
        }// 配置合法性检查
        this.redis = redis;
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        // 默认回满需要 30 秒，闲置 TTL 为 61 秒；禁止使用短于回满时间的固定 TTL。
        this.idleTtlMillis = (long) fillMillis * 2 + 1000;
    }
    // 申请一个 LLM 调用令牌
    public void acquire() {
        List<?> result;
        try {
            result = redis.execute(SCRIPT, List.of(BUCKET_KEY), Integer.toString(capacity),
                    Double.toString(refillPerSecond), Long.toString(idleTtlMillis));
        } catch (DataAccessException exception) {
            log.error("LLM 令牌桶不可用，exceptionType={}", exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "LLM rate limiter is unavailable");
        }
        // 检查 Lua 返回结果
        if (result == null || result.size() != 2 || !(result.get(0) instanceof Long allowed)
                || !(result.get(1) instanceof Long waitMillis) || (allowed != 0 && allowed != 1)
                || (allowed == 0 && waitMillis < 1) || (allowed == 1 && waitMillis != 0)) {
            log.error("LLM 令牌桶返回无效结果");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Invalid LLM rate limiter response");
        }
        if (allowed == 0) { //没令牌
            throw new LlmRateLimitedException(waitMillis);
        }
    }
}
