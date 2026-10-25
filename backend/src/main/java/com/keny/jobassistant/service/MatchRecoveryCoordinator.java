package com.keny.jobassistant.service;

import com.keny.jobassistant.model.enums.MatchStatus;
import com.keny.jobassistant.repository.MatchRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 事后兜底：PROCESSING 超时通常表示 worker 崩溃或被强杀（优雅停机来不及的情况），
 * 清除旧 claim token 后重新入队；次数已经用完的进入 DEAD。
 */
@Slf4j
@Service
public class MatchRecoveryCoordinator {

    private static final int BATCH_SIZE = 100;

    private final MatchRecordRepository matchRecordRepository;
    private final TransactionTemplate transactionTemplate;
    private final Duration processingTimeout;
    private final int maxAttempts;

    public MatchRecoveryCoordinator(MatchRecordRepository matchRecordRepository,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${app.match.worker.processing-timeout-seconds:300}") long processingTimeoutSeconds,
                                    @Value("${app.match.worker.max-attempts:3}") int maxAttempts) {
        this.matchRecordRepository = matchRecordRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.processingTimeout = Duration.ofSeconds(Math.max(1, processingTimeoutSeconds));
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${app.match.worker.recovery-fixed-delay-ms:30000}")
    public void recoverTimedOutTasks() {
        Instant startedBefore = Instant.now().minus(processingTimeout);
        List<Long> candidates = matchRecordRepository.findProcessingTimeoutCandidates(
                MatchStatus.PROCESSING, startedBefore, PageRequest.of(0, BATCH_SIZE));
        candidates.forEach(matchId -> recoverTimedOutTask(matchId, startedBefore));
    }

    /** 加锁后再次检查：这期间 worker 可能刚好完成，不能把已完成的任务改回去。 */
    void recoverTimedOutTask(Long matchId, Instant startedBefore) {
        transactionTemplate.executeWithoutResult(status -> matchRecordRepository.findForUpdateById(matchId).ifPresent(record -> {
            if (record.getStatus() != MatchStatus.PROCESSING || record.getProcessingStartedAt() == null
                    || record.getProcessingStartedAt().isAfter(startedBefore)) {
                return;
            }
            boolean attemptsExhausted = record.getAttemptCount() >= maxAttempts;
            record.setStatus(attemptsExhausted ? MatchStatus.DEAD : MatchStatus.PENDING);
            record.setNextAttemptAt(attemptsExhausted ? null : Instant.now());
            record.setProcessingStartedAt(null);
            record.setClaimToken(null);
            record.setLastErrorCode("PROCESSING_TIMEOUT");
            record.setLastErrorMessage(attemptsExhausted
                    ? "Processing timed out and the maximum attempts were exhausted" : "Processing timed out and was queued again");
            record.setUpdateTime(LocalDateTime.now());
            matchRecordRepository.save(record);
            log.warn("匹配任务处理超时，matchId={}, requeued={}", matchId, !attemptsExhausted);
        }));
    }
}
