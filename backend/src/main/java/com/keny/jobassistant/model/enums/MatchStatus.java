package com.keny.jobassistant.model.enums;

/**
 * 简历与 JD 匹配任务状态。
 */
public enum MatchStatus {

    // 已创建，等待后台 worker 领取。
    PENDING,

    // worker 已领取，正在分析。
    PROCESSING,

    // 分析完成，score 和 analysis_json 已写入。
    COMPLETED,

    // 不可重试的错误或重试次数耗尽，用户可以重新发起。
    DEAD;

    public boolean isTerminal() {
        return this == COMPLETED || this == DEAD;
    }
}
