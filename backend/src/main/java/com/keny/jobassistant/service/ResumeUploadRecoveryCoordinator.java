package com.keny.jobassistant.service;

import com.keny.jobassistant.model.entity.ResumeUploadSession;
import com.keny.jobassistant.model.enums.ResumeUploadStatus;
import com.keny.jobassistant.repository.ResumeUploadSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 恢复冻结复制中断或 worker 处理超时的上传任务。 */
@Slf4j
@Service
public class ResumeUploadRecoveryCoordinator {

    private final ResumeUploadSessionRepository uploadSessionRepository;
    private final ResumeS3StorageService s3StorageService;
    private final TransactionTemplate transactionTemplate;
    // 等待一段时间再恢复，避免把仍在正常复制中的请求误判为崩溃。
    private final Duration minimumAge;
    private final Duration processingTimeout;
    private final int batchSize;
    private final int maxAttempts;

    public ResumeUploadRecoveryCoordinator(ResumeUploadSessionRepository uploadSessionRepository,
                                           ResumeS3StorageService s3StorageService,
                                           PlatformTransactionManager transactionManager,
                                           @Value("${app.resume.s3.freeze-recovery.minimum-age-seconds:30}") long minimumAgeSeconds,
                                           @Value("${app.resume.worker.processing-timeout-seconds:900}") long processingTimeoutSeconds,
                                           @Value("${app.resume.s3.freeze-recovery.batch-size:100}") int batchSize,
                                           @Value("${app.resume.worker.max-attempts:3}") int maxAttempts) {
        this.uploadSessionRepository = uploadSessionRepository;
        this.s3StorageService = s3StorageService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.minimumAge = Duration.ofSeconds(Math.max(1, minimumAgeSeconds));
        this.processingTimeout = Duration.ofSeconds(Math.max(1, processingTimeoutSeconds));
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    /** 定期检查已经保留处理对象 Key 的陈旧 UPLOADING 任务。 */
    @Scheduled(fixedDelayString = "${app.resume.s3.freeze-recovery.fixed-delay-ms:30000}")
    public void recoverCopiedUploads() {
        recoverFrozenCopies();
        recoverTimedOutTasks();
    }

    private void recoverFrozenCopies() {
        Instant updatedBefore = Instant.now().minus(minimumAge);
        // 每轮限制数量，避免历史异常任务一次占用过多 S3 和数据库连接。
        List<ResumeUploadSession> candidates = uploadSessionRepository.findFreezeRecoveryCandidates(
                ResumeUploadStatus.UPLOADING, updatedBefore, PageRequest.of(0, batchSize));
        for (ResumeUploadSession candidate : candidates) {
            recoverCandidate(candidate);
        }
    }

    /** PROCESSING 超时通常表示 worker 崩溃，清除旧 claim token 后重新入队。 */
    private void recoverTimedOutTasks() {
        Instant startedBefore = Instant.now().minus(processingTimeout);
        List<ResumeUploadSession> candidates = uploadSessionRepository.findProcessingTimeoutCandidates(
                ResumeUploadStatus.PROCESSING, startedBefore, PageRequest.of(0, batchSize));
        for (ResumeUploadSession candidate : candidates) {
            recoverTimedOutTask(candidate.getId(), startedBefore);
        }
    }

    private void recoverTimedOutTask(UUID uploadId, Instant startedBefore) {
        transactionTemplate.executeWithoutResult(status -> uploadSessionRepository.findForUpdateById(uploadId).ifPresent(session -> {
            if (session.getStatus() != ResumeUploadStatus.PROCESSING || session.getProcessingStartedAt() == null
                    || session.getProcessingStartedAt().isAfter(startedBefore)) {
                return;
            }
            boolean attemptsExhausted = Objects.requireNonNullElse(session.getAttemptCount(), 0) >= maxAttempts;
            Instant now = Instant.now();
            session.setStatus(attemptsExhausted ? ResumeUploadStatus.DEAD : ResumeUploadStatus.PENDING);
            session.setNextAttemptAt(attemptsExhausted ? null : now);
            session.setProcessingStartedAt(null);
            session.setClaimToken(null);
            session.setLastErrorCode("PROCESSING_TIMEOUT");
            session.setLastErrorMessage(attemptsExhausted
                    ? "Processing timed out and the maximum attempts were exhausted" : "Processing timed out and was queued again");
            session.setUpdateTime(now);
            uploadSessionRepository.save(session);
        }));
    }

    private void recoverCandidate(ResumeUploadSession candidate) {
        try {
            // 只有处理对象真实存在且元数据匹配时才能恢复，不能仅凭数据库中的 Key 入队。
            s3StorageService.findObjectMetadata(candidate.getProcessingObjectKey())
                    .filter(metadata -> matchesExpectedMetadata(candidate, metadata))
                    .ifPresent(metadata -> markPending(candidate.getId(), candidate.getProcessingObjectKey()));
        } catch (RuntimeException exception) {
            log.warn("恢复已冻结上传任务失败，uploadId={}", candidate.getId(), exception);
        }
    }

    private boolean matchesExpectedMetadata(ResumeUploadSession candidate, ResumeS3StorageService.StoredObjectMetadata metadata) {
        boolean matches = metadata.contentLength() == candidate.getExpectedSize()
                && candidate.getExpectedContentType().equalsIgnoreCase(metadata.contentType());
        if (!matches) {
            log.warn("处理对象元数据与上传会话不一致，暂不恢复，uploadId={}", candidate.getId());
        }
        return matches;
    }

    /** 在短事务中重新校验状态和对象 Key，再恢复到 PENDING。 */
    private void markPending(UUID uploadId, String processingObjectKey) {
        transactionTemplate.executeWithoutResult(status -> uploadSessionRepository.findForUpdateById(uploadId).ifPresent(session -> {
            // 候选查询后状态可能已经变化，锁内条件可以避免覆盖 complete 或 worker 的新结果。
            if (session.getStatus() != ResumeUploadStatus.UPLOADING
                    || !Objects.equals(session.getProcessingObjectKey(), processingObjectKey)) {
                return;
            }
            Instant now = Instant.now();
            session.setStatus(ResumeUploadStatus.PENDING);
            // 恢复任务无需等待重试退避，可以立即被 worker 领取。
            session.setNextAttemptAt(now);
            session.setProcessingStartedAt(null);
            session.setClaimToken(null);
            session.setLastErrorCode(null);
            session.setLastErrorMessage(null);
            session.setUpdateTime(now);
            uploadSessionRepository.save(session);
        }));
    }
}
