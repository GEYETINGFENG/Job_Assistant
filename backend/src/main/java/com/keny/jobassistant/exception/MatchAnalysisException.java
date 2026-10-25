package com.keny.jobassistant.exception;

import lombok.Getter;

/**
 * 匹配分析失败。retryable 决定 worker 是按退避重试还是直接进入 DEAD：
 * 超时、5xx、429、输出格式错误等偶发问题可以重试；
 * 401/403 等请求本身被拒绝的错误重试也不会成功，直接 DEAD，避免白白消耗额度。
 */
@Getter
public class MatchAnalysisException extends RuntimeException {

    private final String errorCode;
    private final boolean retryable;

    public MatchAnalysisException(String errorCode, String message, boolean retryable) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }
}
