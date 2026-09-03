package com.keny.jobassistant.service;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
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

/** 后台处理已经冻结并进入 PENDING 队列的简历上传任务。 */
@Slf4j
@Service
public class ResumeUploadWorker {

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
            Optional<ResumeUploadTaskClaim> claim = claimNextTask();
            if (claim.isEmpty()) {
                return;
            }
            processClaimedTask(claim.get());
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
