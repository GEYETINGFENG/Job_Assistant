package com.keny.jobassistant.exception;

/** 令牌不足只延后任务，不占用业务失败重试次数。 */
public class LlmRateLimitedException extends RuntimeException {
    private final long retryAfterMillis;

    public LlmRateLimitedException(long retryAfterMillis) {
        super("LLM rate limit reached");
        this.retryAfterMillis = Math.max(1, retryAfterMillis);
    }

    public long getRetryAfterMillis() {
        return retryAfterMillis;
    }
}
