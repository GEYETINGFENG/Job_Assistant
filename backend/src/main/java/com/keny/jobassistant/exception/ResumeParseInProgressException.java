package com.keny.jobassistant.exception;

/**
 * 同一份内容正在被另一个任务解析（防缓存击穿的互斥锁被占用）。
 * 与限流一样只延后任务、不占用失败重试次数：稍后重试时，对方的结果已经进了缓存。
 */
public class ResumeParseInProgressException extends LlmRateLimitedException {

    public ResumeParseInProgressException(long retryAfterMillis) {
        super(retryAfterMillis);
    }
}
