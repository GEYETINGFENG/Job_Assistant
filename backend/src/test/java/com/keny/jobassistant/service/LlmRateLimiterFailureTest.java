package com.keny.jobassistant.service;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LlmRateLimiterFailureTest {
    @Test
    void redisOutageShouldBeRetryableSystemError() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("test outage"));
        var error = catchThrowableOfType(new LlmRateLimiter(redis, 3, 0.1)::acquire, BusinessException.class);
        assertThat(error.getCode()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
    }

    @Test
    void missingScriptResultShouldNeverAllowRequest() {
        var limiter = new LlmRateLimiter(mock(StringRedisTemplate.class), 3, 0.1);
        assertThatThrownBy(limiter::acquire).isInstanceOf(BusinessException.class);
    }

    @Test
    void invalidConfigurationShouldFailAtStartup() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        assertThatThrownBy(() -> new LlmRateLimiter(redis, 0, 0.1)).isInstanceOf(IllegalArgumentException.class);
        for (double rate : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.MIN_VALUE}) {
            assertThatThrownBy(() -> new LlmRateLimiter(redis, 3, rate)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
