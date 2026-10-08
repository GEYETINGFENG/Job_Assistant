package com.keny.jobassistant.service;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.exception.LlmRateLimitedException;
import com.keny.jobassistant.model.document.ResumeParseResult;
import com.keny.jobassistant.model.entity.Resume;
import com.keny.jobassistant.model.entity.ResumeUploadSession;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.model.enums.ResumeUploadStatus;
import com.keny.jobassistant.model.enums.ResumeUploadType;
import com.keny.jobassistant.model.task.ResumeUploadTaskClaim;
import com.keny.jobassistant.repository.ResumeRepository;
import com.keny.jobassistant.repository.ResumeUploadSessionRepository;
import com.keny.jobassistant.repository.ResumeUploadTaskClaimRepository;
import com.keny.jobassistant.repository.ResumeVersionAtomicRepository;
import com.keny.jobassistant.repository.UserRepository;
import com.keny.jobassistant.service.support.PathBackedMultipartFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 后台处理已经冻结并进入 PENDING 队列的简历上传任务。
 *
 * 优雅停机：收到 SIGTERM 后 Spring 在生命周期停止阶段调用 stop(callback)，
 * worker 立即停止领取新任务，等手上的任务处理完再通知 Spring 继续关闭。
 * 超过 spring.lifecycle.timeout-per-shutdown-phase 仍未完成的任务会被强制中断，
 * 由 ResumeUploadRecoveryCoordinator 按处理超时重新入队。
 */
@Slf4j
@Service
public class ResumeUploadWorker implements SmartLifecycle {

    private static final int DEFAULT_RESUME_STATUS = 0;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 512;

    private final ResumeUploadTaskClaimRepository taskClaimRepository;
    private final ResumeUploadSessionRepository uploadSessionRepository;
    private final ResumeS3StorageService s3StorageService;
    private final ResumeParserService resumeParserService;
    private final ResumeRepository resumeRepository;
    private final ResumeVersionAtomicRepository resumeVersionAtomicRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    private final String finalPrefix;
    private final int batchSize;
    private final int maxAttempts;
    private final long retryBaseDelaySeconds;
    private final long retryMaxDelaySeconds;

    /** 保护停机状态：接单开关、处理中任务数和等待回调必须一起读写，避免停机与领取任务竞争。 */
    private final Object lifecycleLock = new Object();
    private boolean acceptingTasks = true;
    private int inFlightTasks;
    private Runnable stopCallback;
    private volatile boolean running;

    public ResumeUploadWorker(ResumeUploadTaskClaimRepository taskClaimRepository,
                              ResumeUploadSessionRepository uploadSessionRepository,
                              ResumeS3StorageService s3StorageService,
                              ResumeParserService resumeParserService,
                              ResumeRepository resumeRepository,
                              ResumeVersionAtomicRepository resumeVersionAtomicRepository,
                              UserRepository userRepository,
                              PlatformTransactionManager transactionManager,
                              @Value("${app.resume.s3.final-prefix:resumes}") String finalPrefix,
                              @Value("${app.resume.worker.batch-size:3}") int batchSize,
                              @Value("${app.resume.worker.max-attempts:3}") int maxAttempts,
                              @Value("${app.resume.worker.retry-base-delay-seconds:30}") long retryBaseDelaySeconds,
                              @Value("${app.resume.worker.retry-max-delay-seconds:600}") long retryMaxDelaySeconds) {
        this.taskClaimRepository = taskClaimRepository;
        this.uploadSessionRepository = uploadSessionRepository;
        this.s3StorageService = s3StorageService;
        this.resumeParserService = resumeParserService;
        this.resumeRepository = resumeRepository;
        this.resumeVersionAtomicRepository = resumeVersionAtomicRepository;
        this.userRepository = userRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.finalPrefix = finalPrefix;
        this.batchSize = Math.max(1, batchSize);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBaseDelaySeconds = Math.max(1, retryBaseDelaySeconds);
        this.retryMaxDelaySeconds = Math.max(this.retryBaseDelaySeconds, retryMaxDelaySeconds);
    }

    /** 每轮领取少量任务顺序处理；多实例部署时 SKIP LOCKED 会让不同实例领取不同记录。 */
    @Scheduled(fixedDelayString = "${app.resume.worker.fixed-delay-ms:1000}")
    public void processPendingUploads() {
        for (int index = 0; index < batchSize; index++) {
            // 每次领取前检查接单开关；停机后本轮剩余名额直接放弃，留给其他实例处理。
            if (!tryBeginTask()) {
                return;
            }
            try {
                Optional<ResumeUploadTaskClaim> claim = claimNextTask();
                if (claim.isEmpty()) {
                    return;
                }
                processClaimedTask(claim.get());
            } finally {
                finishTask();
            }
        }
    }

    @Override
    public void start() {
        synchronized (lifecycleLock) {
            acceptingTasks = true;
            running = true;
        }
    }

    @Override
    public void stop() {
        synchronized (lifecycleLock) {
            acceptingTasks = false;
            running = false;
        }
    }

    /** 停止接单；若仍有任务在处理，等最后一个任务结束后再回调，让 Spring 在此之前不销毁数据源等依赖。 */
    @Override
    public void stop(Runnable callback) {
        synchronized (lifecycleLock) {
            acceptingTasks = false;
            running = false;
            if (inFlightTasks > 0) {
                log.info("收到停机信号，worker 停止领取新任务，等待处理中任务完成，inFlight={}", inFlightTasks);
                stopCallback = callback;
                return;
            }
        }
        log.info("收到停机信号，worker 没有处理中任务，立即停止");
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 领取前登记处理中任务；登记和检查开关在同一把锁内，保证停机后不会再有新任务开始。 */
    private boolean tryBeginTask() {
        synchronized (lifecycleLock) {
            if (!acceptingTasks) {
                return false;
            }
            inFlightTasks++;
            return true;
        }
    }

    private void finishTask() {
        Runnable callback = null;
        synchronized (lifecycleLock) {
            inFlightTasks--;
            if (inFlightTasks == 0 && stopCallback != null) {
                callback = stopCallback;
                stopCallback = null;
            }
        }
        if (callback != null) {
            log.info("处理中任务已全部完成，worker 已安全停止");
            callback.run();
        }
    }

    /** 领取操作只修改一行数据库，事务提交后才开始耗时的 S3、Tika 和 LLM 工作。 */
    private Optional<ResumeUploadTaskClaim> claimNextTask() {
        ResumeUploadTaskClaim claim = transactionTemplate.execute(status -> taskClaimRepository.claimNext(UUID.randomUUID()).orElse(null));
        return Optional.ofNullable(claim);
    }

    private void processClaimedTask(ResumeUploadTaskClaim claim) {
        Path temporaryFile = null;
        String finalObjectKey = null;
        try {
            temporaryFile = Files.createTempFile("resume-worker-", claim.expectedExtension());
            s3StorageService.downloadObject(claim.processingObjectKey(), temporaryFile);
            if (Files.size(temporaryFile) != claim.expectedSize()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "Frozen resume size does not match the upload task");
            }

            // PathBackedMultipartFile 只包装临时文件路径，解析器按流读取，不会把整个文件一次放进内存。
            MultipartFile multipartFile = new PathBackedMultipartFile("file", claim.originalFilename(),
                    claim.expectedContentType(), temporaryFile);
            ResumeParseResult parseResult = resumeParserService.parseResume(multipartFile);
            if (!claim.expectedExtension().equals(parseResult.extension())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "Parsed resume type does not match the upload task");
            }

            finalObjectKey = "%s/%d/%s/source%s".formatted(finalPrefix, claim.userId(), claim.uploadId(), parseResult.extension());
            s3StorageService.uploadValidatedObject(finalObjectKey, temporaryFile, parseResult.mediaType());
            ProcessingResult result = saveCompletedTask(claim, parseResult, finalObjectKey);

            // 数据库已经记录最终对象后再清理临时对象，清理失败只记日志，不影响完成结果。
            s3StorageService.deleteObjectQuietly(claim.processingObjectKey());
            s3StorageService.deleteObjectQuietly(claim.uploadObjectKey());
            log.info("简历上传任务处理完成，uploadId={}, resumeId={}, version={}", claim.uploadId(), result.resumeId(), result.versionNumber());
        } catch (LlmRateLimitedException exception) {
            deferRateLimitedTask(claim, exception.getRetryAfterMillis());
        } catch (BusinessException exception) {
            handleFailure(claim, Integer.toString(exception.getCode()), businessErrorMessage(exception),
                    isRetryable(exception), finalObjectKey, exception);
        } catch (IOException exception) {
            handleFailure(claim, Integer.toString(ErrorCode.SYSTEM_ERROR.getCode()), "Failed to use temporary resume file",
                    true, finalObjectKey, exception);
        } catch (RuntimeException exception) {
            handleFailure(claim, Integer.toString(ErrorCode.SYSTEM_ERROR.getCode()), "Unexpected resume processing failure",
                    true, finalObjectKey, exception);
        } finally {
            deleteTemporaryFileQuietly(temporaryFile);
        }
    }

    /** 限流只重新排队；行锁和 claim token 保证旧 worker 不能撤销新 worker 的领取计数。 */
    private void deferRateLimitedTask(ResumeUploadTaskClaim claim, long retryAfterMillis) {
        long delayMillis = Math.max(1000, retryAfterMillis);
        Boolean deferred = transactionTemplate.execute(status -> {
            ResumeUploadSession session = uploadSessionRepository.findForUpdateById(claim.uploadId()).orElse(null);
            if (session == null || session.getStatus() != ResumeUploadStatus.PROCESSING
                    || !Objects.equals(session.getClaimToken(), claim.claimToken())) {
                return false;
            }
            Instant now = Instant.now();
            session.setStatus(ResumeUploadStatus.PENDING);
            session.setAttemptCount(Math.max(0, session.getAttemptCount() - 1));
            session.setNextAttemptAt(now.plusMillis(delayMillis));
            session.setProcessingStartedAt(null);
            session.setClaimToken(null);
            session.setLastErrorCode("LLM_RATE_LIMITED");
            session.setLastErrorMessage("Waiting for LLM request quota");
            session.setUpdateTime(now);
            uploadSessionRepository.save(session);
            return true;
        });
        if (Boolean.TRUE.equals(deferred)) {
            log.info("简历任务等待 LLM 额度，uploadId={}, retryAfterMillis={}", claim.uploadId(), delayMillis);
        } else {
            log.info("忽略失效 worker 的限流结果，uploadId={}, claimToken={}", claim.uploadId(), claim.claimToken());
        }
    }

    /** 最终提交时再次核对 claim token，超时 worker 即使晚到也不能覆盖新 worker 的结果。 */
    private ProcessingResult saveCompletedTask(ResumeUploadTaskClaim claim, ResumeParseResult parseResult, String finalObjectKey) {
        ProcessingResult result = transactionTemplate.execute(status -> {
            ResumeUploadSession session = uploadSessionRepository.findForUpdateById(claim.uploadId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Upload task does not exist"));
            if (session.getStatus() != ResumeUploadStatus.PROCESSING || !Objects.equals(session.getClaimToken(), claim.claimToken())) {
                throw new BusinessException(ErrorCode.UPLOAD_CONFLICT, "Upload task is no longer owned by this worker");
            }

            Long resumeId = claim.uploadType() == ResumeUploadType.CREATE ? createResume(claim) : claim.targetResumeId();
            if (resumeId == null) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Target resume ID is missing");
            }
            String fileUrl = "/resumes/" + resumeId + "/file";
            Integer versionNumber = resumeVersionAtomicRepository
                    .createNextVersion(resumeId, claim.userId(), claim.resumeName(), fileUrl, parseResult.parsedJson())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Target resume does not exist"));
            if (claim.uploadType() == ResumeUploadType.CREATE && versionNumber != 1) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Initial resume version must be version 1");
            }

            session.setResumeId(resumeId);
            session.setVersionNumber(versionNumber);
            session.setFinalObjectKey(finalObjectKey);
            session.setStatus(ResumeUploadStatus.COMPLETED);
            session.setNextAttemptAt(null);
            session.setProcessingStartedAt(null);
            session.setClaimToken(null);
            session.setLastErrorCode(null);
            session.setLastErrorMessage(null);
            session.setUpdateTime(Instant.now());
            uploadSessionRepository.save(session);
            return new ProcessingResult(resumeId, versionNumber);
        });
        if (result == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to save completed upload task");
        }
        return result;
    }

    /** CREATE 任务先建立空的 Resume 主记录，随后由原子 SQL 创建第一个版本。 */
    private Long createResume(ResumeUploadTaskClaim claim) {
        User user = userRepository.findById(claim.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Upload task user does not exist"));
        LocalDateTime now = LocalDateTime.now();
        Resume resume = new Resume();
        resume.setUser(user);
        resume.setResumeName(claim.resumeName());
        resume.setFileUrl(null);
        resume.setParsedJson(null);
        resume.setStatus(DEFAULT_RESUME_STATUS);
        resume.setLatestVersionNumber(0);
        resume.setCreateTime(now);
        resume.setUpdateTime(now);
        return resumeRepository.saveAndFlush(resume).getId();
    }

    private void handleFailure(ResumeUploadTaskClaim claim, String errorCode, String errorMessage, boolean retryable,
                               String finalObjectKey, Exception exception) {
        FailureOutcome outcome = markTaskFailed(claim, errorCode, errorMessage, retryable);
        if (outcome == FailureOutcome.DEAD) {
            s3StorageService.deleteObjectQuietly(finalObjectKey);
            // DEAD 不会再由 worker 重试，可以清理 staging 和 processing 对象，避免长期占用 S3 空间。
            s3StorageService.deleteObjectQuietly(claim.processingObjectKey());
            s3StorageService.deleteObjectQuietly(claim.uploadObjectKey());
        }
        if (outcome == FailureOutcome.RETRY) {
            log.warn("简历上传任务处理失败，稍后重试，uploadId={}, attempt={}", claim.uploadId(), claim.attemptCount(), exception);
        } else if (outcome == FailureOutcome.DEAD) {
            log.warn("简历上传任务进入 DEAD，uploadId={}, attempt={}", claim.uploadId(), claim.attemptCount(), exception);
        } else {
            log.info("忽略已经失效的 worker 结果，uploadId={}, claimToken={}", claim.uploadId(), claim.claimToken());
        }
    }

    /** 临时错误按指数退避重新入队，永久错误或达到最大次数时进入 DEAD。 */
    private FailureOutcome markTaskFailed(ResumeUploadTaskClaim claim, String errorCode, String errorMessage, boolean retryable) {
        FailureOutcome outcome = transactionTemplate.execute(status -> {
            ResumeUploadSession session = uploadSessionRepository.findForUpdateById(claim.uploadId()).orElse(null);
            if (session == null || session.getStatus() != ResumeUploadStatus.PROCESSING
                    || !Objects.equals(session.getClaimToken(), claim.claimToken())) {
                return FailureOutcome.STALE;
            }
            boolean shouldRetry = retryable && claim.attemptCount() < maxAttempts;
            Instant now = Instant.now();
            session.setStatus(shouldRetry ? ResumeUploadStatus.PENDING : ResumeUploadStatus.DEAD);
            session.setNextAttemptAt(shouldRetry ? now.plusSeconds(retryDelaySeconds(claim.attemptCount())) : null);
            session.setProcessingStartedAt(null);
            session.setClaimToken(null);
            session.setLastErrorCode(errorCode);
            session.setLastErrorMessage(truncateErrorMessage(errorMessage));
            session.setUpdateTime(now);
            uploadSessionRepository.save(session);
            return shouldRetry ? FailureOutcome.RETRY : FailureOutcome.DEAD;
        });
        return outcome == null ? FailureOutcome.STALE : outcome;
    }

    private long retryDelaySeconds(int attemptCount) {
        long delay = retryBaseDelaySeconds;
        for (int index = 1; index < attemptCount && delay < retryMaxDelaySeconds; index++) {
            delay = Math.min(retryMaxDelaySeconds, delay > retryMaxDelaySeconds / 2 ? retryMaxDelaySeconds : delay * 2);
        }
        return delay;
    }

    private boolean isRetryable(BusinessException exception) {
        return exception.getCode() == ErrorCode.SYSTEM_ERROR.getCode()
                || exception.getCode() == ErrorCode.OPTIMISTIC_LOCK_CONFLICT.getCode();
    }

    private String businessErrorMessage(BusinessException exception) {
        return StringUtils.isNotBlank(exception.getDescription()) ? exception.getDescription() : exception.getMessage();
    }

    private String truncateErrorMessage(String errorMessage) {
        if (StringUtils.isBlank(errorMessage)) {
            return "Resume processing failed";
        }
        String normalizedMessage = errorMessage.strip();
        return normalizedMessage.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? normalizedMessage : normalizedMessage.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    private void deleteTemporaryFileQuietly(Path temporaryFile) {
        if (temporaryFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException exception) {
            log.warn("删除 worker 临时文件失败，path={}", temporaryFile, exception);
        }
    }

    private enum FailureOutcome {
        RETRY,
        DEAD,
        STALE
    }

    private record ProcessingResult(Long resumeId, Integer versionNumber) {
    }
}
