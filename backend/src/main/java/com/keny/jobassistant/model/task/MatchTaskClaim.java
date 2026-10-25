package com.keny.jobassistant.model.task;

import java.util.UUID;

/**
 * worker 领取到的一条匹配任务。claimToken 在提交结果时用于确认任务仍归当前 worker 所有。
 */
public record MatchTaskClaim(Long matchId, UUID claimToken, int attemptCount, Long userId, Long resumeVersionId, Long jobId) {
}
