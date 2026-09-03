package com.keny.jobassistant.service;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.dto.PresignResumeUploadResponse;
import com.keny.jobassistant.model.dto.ResumeUploadCompleteResponse;
import com.keny.jobassistant.model.dto.ResumeUploadStatusResponse;
import com.keny.jobassistant.model.entity.Resume;
import com.keny.jobassistant.model.entity.ResumeUploadSession;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.model.entity.request.PresignResumeUploadRequest;
import com.keny.jobassistant.model.enums.ResumeUploadStatus;
import com.keny.jobassistant.model.enums.ResumeUploadType;
import com.keny.jobassistant.repository.ResumeRepository;
import com.keny.jobassistant.repository.ResumeUploadSessionRepository;
import com.keny.jobassistant.repository.UserRepository;
import com.keny.jobassistant.security.CurrentUserProvider;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * S3 预签名简历上传业务服务。
 */
@Service
public class ResumeS3UploadService {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final String PDF_EXTENSION = ".pdf";
    private static final String DOCX_EXTENSION = ".docx";
    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String DOCX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ResumeS3StorageService s3StorageService;
    private final ResumeUploadSessionRepository uploadSessionRepository;
    private final ResumeRepository resumeRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final TransactionTemplate transactionTemplate;
    private final long presignDurationMinutes;
    private final String stagingPrefix;
    private final String processingPrefix;

    public ResumeS3UploadService(ResumeS3StorageService s3StorageService,
                                 ResumeUploadSessionRepository uploadSessionRepository,
                                 ResumeRepository resumeRepository,
                                 UserRepository userRepository,
                                 CurrentUserProvider currentUserProvider,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${app.resume.s3.presign-duration-minutes:5}") long presignDurationMinutes,
                                 @Value("${app.resume.s3.staging-prefix:resume-uploads}") String stagingPrefix,
                                 @Value("${app.resume.s3.processing-prefix:resume-processing}") String processingPrefix) {
        this.s3StorageService = s3StorageService;
        this.uploadSessionRepository = uploadSessionRepository;
        this.resumeRepository = resumeRepository;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.presignDurationMinutes = presignDurationMinutes;
        this.stagingPrefix = stagingPrefix;
        this.processingPrefix = processingPrefix;
    }
    /**
     * 创建新简历的预签名上传会话。
     */
    public PresignResumeUploadResponse createPresignedUpload(String idempotencyKey, PresignResumeUploadRequest request) {
        validateIdempotencyKey(idempotencyKey);
        return createPresignedUploadSession(request, ResumeUploadType.CREATE, null, idempotencyKey.strip());
    }

    /**
     * 为已有简历创建新版本上传会话。
     */
    public PresignResumeUploadResponse createPresignedVersionUpload(Long resumeId, String idempotencyKey, PresignResumeUploadRequest request) {
        validateResumeId(resumeId);
        validateIdempotencyKey(idempotencyKey);
        return createPresignedUploadSession(request, ResumeUploadType.NEW_VERSION, resumeId, idempotencyKey.strip());
    }
    /**
     * 创建上传会话并返回 S3 PUT 预签名 URL,该方法不会直接上传文件到 S3，而是完成上传前的准备工作：
     * 1. 校验用户提交的文件信息
     * 2. 创建临时 S3 对象 Key
     * 3. 生成短期 PUT 预签名 URL
     * 4. 保存上传会话 (upload session)
     * 5. 返回上传地址给客户端
     */
    private PresignResumeUploadResponse createPresignedUploadSession(PresignResumeUploadRequest request, ResumeUploadType uploadType, Long targetResumeId,String idempotencyKey) {
        validatePresignRequest(request); //基础参数校验
        Long currentUserId = currentUserProvider.getCurrentUserId();
        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_LOGIN));
        // 为已有简历上传新版本时，必须先确认目标简历属于当前用户。
        if (uploadType == ResumeUploadType.NEW_VERSION && !resumeRepository.existsByIdAndUser_IdAndIsDelete(targetResumeId, currentUserId, Resume.NOT_DELETED)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        // CREATE 和 NEW_VERSION 都先检查幂等键，重复请求继续使用第一次创建的 uploadId。
        Optional<ResumeUploadSession> existingSession = findIdempotentSession(currentUserId, uploadType, targetResumeId, idempotencyKey);
        if (existingSession.isPresent()) {
            return reuseIdempotentUploadSession(existingSession.get(), request);
        }

        // 根据文件扩展名确定允许的文件类型
        String extension = resolveAllowedExtension(request.filename());
        // 根据扩展名确定上传Content-Type
        String contentType = resolveContentType(extension);
        // 创建唯一上传ID, UUID作为一次上传会话的唯一标识,后续接口里面的 POST /uploads/{uploadId}/complete
        UUID uploadId = UUID.randomUUID();
        //第一次看到这个Idempotency-Key → 随机生成一个uploadId → 把 Idempotency-Key 和 uploadId 一起存数据库
        //以后再次看到相同 Idempotency-Key → 不再 UUID.randomUUID() → 从数据库找到第一次的 uploadId → 直接复用
        // 生成S3临时对象Key
        //  eg: resume-uploads/
        //      2/
        //      ff17262-xxxx/
        //      source.pdf
        // staging目录表示：当前文件还没有完成验证。
        String objectKey = "%s/%d/%s/source%s".formatted(stagingPrefix, currentUserId, uploadId, extension);
        Duration duration = Duration.ofMinutes(presignDurationMinutes); //设置预签名URL有效时间。
        //调用S3服务生成PUT预签名URL
        ResumeS3StorageService.PresignedUploadResult result = s3StorageService.createPresignedUpload(objectKey, contentType, duration);
        Instant now = Instant.now();
        // 创建上传会话记录,存入数据库

        ResumeUploadSession session = new ResumeUploadSession();
        session.setId(uploadId); //上传会话ID，与返回给客户端的uploadId一致
        session.setUser(currentUser);
        session.setResumeName(request.resumeName().strip()); //保存用户填写的简历名称
        session.setOriginalFilename(request.filename()); //保存用户原始文件名
        session.setUploadObjectKey(objectKey); //保存S3临时对象Key
        session.setProcessingObjectKey(null);
        session.setFinalObjectKey(null);
        session.setExpectedExtension(extension); //这里的extension还没有被tika验证过，只是初步上传
        session.setExpectedContentType(contentType);
        session.setExpectedSize(request.fileSize());
        session.setStatus(ResumeUploadStatus.UPLOADING);
        session.setUploadType(uploadType);
        session.setResumeId(targetResumeId);
        session.setVersionNumber(null);
        session.setIdempotencyKey(idempotencyKey);
        session.setAttemptCount(0);
        session.setNextAttemptAt(null);
        session.setProcessingStartedAt(null);
        session.setClaimToken(null);
        session.setLastErrorCode(null);
        session.setLastErrorMessage(null);
        session.setExpiresAt(result.expiresAt());
        session.setCreateTime(now);
        session.setUpdateTime(now);
        try {
            /* saveAndFlush 的原因：
             * 相同 Idempotency-Key 如果并发请求，数据库 UNIQUE 约束必须在这里立即执行。
             */
            uploadSessionRepository.saveAndFlush(session);
        } catch (DataIntegrityViolationException exception) {
            /*
             * 两个相同 key 同时请求时可能发生：
             * A：查询不存在  B：查询也不存在
             * A：INSERT 成功
             * B：INSERT 触发 UNIQUE
             * B 不创建新任务，而是重新找到 A 创建的任务并复用。
             */
            if (idempotencyKey != null) {
                Optional<ResumeUploadSession> concurrentSession = findIdempotentSession(currentUserId, uploadType, targetResumeId, idempotencyKey);
                if (concurrentSession.isPresent()) {
                    return reuseIdempotentUploadSession(concurrentSession.get(), request);
                }
            }
            throw exception;
        }
        return new PresignResumeUploadResponse(uploadId, result.uploadUrl(), result.expiresAt(), result.requiredHeaders());
    }

    /** 按上传类型选择正确的幂等查询，因为 CREATE 任务还没有 resumeId。 */
    private Optional<ResumeUploadSession> findIdempotentSession(Long userId, ResumeUploadType uploadType, Long resumeId,
                                                                 String idempotencyKey) {
        if (uploadType == ResumeUploadType.CREATE) {
            return uploadSessionRepository.findCreateByIdempotencyKey(userId, uploadType, idempotencyKey);
        }
        return uploadSessionRepository.findByIdempotencyKey(userId, resumeId, uploadType, idempotencyKey);
    }

    /**
     * 处理重复的 Idempotency-Key。
     * UPLOADING：复用原 uploadId，并重新生成同一个 staging Key 的预签名 URL。
     * DEAD、EXPIRED 或之前的 URL 已经过期时，也复用原业务操作重新上传。
     * PENDING、PROCESSING 表示文件已经进入处理流程，不再发放可覆盖文件的新 URL。
     * uploadId 不变，所以仍然属于同一次业务操作。
     */
    private PresignResumeUploadResponse reuseIdempotentUploadSession(ResumeUploadSession session, PresignResumeUploadRequest request) {
        validateSameIdempotentRequest(session, request);
        if (session.getStatus() == ResumeUploadStatus.COMPLETED) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "Idempotency key has already completed version " + session.getVersionNumber()
            );
        }
        if (session.getStatus() == ResumeUploadStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Idempotent upload request is already processing");
        }
        if (session.getStatus() == ResumeUploadStatus.PENDING) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Idempotent upload request is already queued for processing");
        }
        if (session.getStatus() == ResumeUploadStatus.UPLOADING && session.getProcessingObjectKey() != null) {
            throw new BusinessException(ErrorCode.UPLOAD_CONFLICT, "Upload request is already being confirmed");
        }
        Duration duration = Duration.ofMinutes(presignDurationMinutes);
        ResumeS3StorageService.PresignedUploadResult result = s3StorageService.createPresignedUpload(
                session.getUploadObjectKey(),
                session.getExpectedContentType(),
                duration
        );
        session.setStatus(ResumeUploadStatus.UPLOADING);
        session.setProcessingObjectKey(null);
        session.setFinalObjectKey(null);
        session.setAttemptCount(0);
        session.setNextAttemptAt(null);
        session.setProcessingStartedAt(null);
        session.setClaimToken(null);
        session.setLastErrorCode(null);
        session.setLastErrorMessage(null);
        session.setExpiresAt(result.expiresAt());
        session.setUpdateTime(Instant.now());
        uploadSessionRepository.save(session);
        return new PresignResumeUploadResponse(
                session.getId(),
                result.uploadUrl(),
                result.expiresAt(),
                result.requiredHeaders()
        );
    }

    /**
     * 同一个 Idempotency-Key 不允许代表两个不同请求。
     * 例如：
     * 第一次：key=abc + resume.pdf
     * 第二次：key=abc + another.pdf
     * 这种情况属于客户端错误，必须使用新的 key。
     */
    private void validateSameIdempotentRequest(ResumeUploadSession session, PresignResumeUploadRequest request) {
        String resumeName = request.resumeName().strip();
        boolean sameRequest = Objects.equals(session.getResumeName(), resumeName)
                && Objects.equals(session.getOriginalFilename(), request.filename())
                && Objects.equals(session.getExpectedSize(), request.fileSize());
        if (!sameRequest) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "The same Idempotency-Key cannot be reused for a different upload request");
        }
    }

    /**
     * 快速确认客户端上传已经完成，并将不可变的处理对象加入后台队列。
     * 请求线程只读取元数据、执行带 ETag 条件的 S3 复制以及更新任务状态，不解析文件或创建简历版本。
     */
    public ResumeUploadCompleteResponse completeUpload(UUID uploadId) {
        if (uploadId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Upload ID cannot be null");
        }
        Long currentUserId = currentUserProvider.getCurrentUserId();
        // 第一段短事务只锁定任务、校验状态并保存处理对象 Key，不在事务中调用 S3。
        CompletePreparation preparation = prepareUploadConfirmation(uploadId, currentUserId);
        if (preparation.response() != null) {
            // 重复调用遇到 PENDING、PROCESSING 或 COMPLETED 时，直接返回数据库里的同一任务。
            return preparation.response();
        }

        UploadConfirmation confirmation = preparation.confirmation();
        // 先检查冻结对象是否已经存在，以覆盖“复制成功后进程崩溃”的恢复场景。
        Optional<ResumeS3StorageService.StoredObjectMetadata> frozenMetadata = s3StorageService.findObjectMetadata(confirmation.processingObjectKey());
        if (frozenMetadata.isEmpty()) {
            // 只读取源对象元数据，不在 complete 请求中下载或解析文件正文。
            ResumeS3StorageService.StoredObjectMetadata uploadMetadata = s3StorageService.getObjectMetadata(confirmation.uploadObjectKey());
            validateUploadedObject(confirmation, uploadMetadata);
            try {
                s3StorageService.copyObjectIfMatch(confirmation.uploadObjectKey(), confirmation.processingObjectKey(), uploadMetadata.eTag());
            } catch (BusinessException exception) {
                // 并发确认可能已经先完成同一目标 Key 的复制；存在且元数据正确时按同一任务继续入队。
                if (exception.getCode() != ErrorCode.UPLOAD_CONFLICT.getCode()) {
                    throw exception;
                }
                Optional<ResumeS3StorageService.StoredObjectMetadata> concurrentCopy = s3StorageService.findObjectMetadata(confirmation.processingObjectKey());
                if (concurrentCopy.isEmpty()) {
                    throw exception;
                }
                validateUploadedObject(confirmation, concurrentCopy.get());
            }
        } else {
            // 处理对象已经存在，说明上次请求可能在复制成功后、提交 PENDING 前中断，直接恢复入队。
            validateUploadedObject(confirmation, frozenMetadata.get());
        }
        // S3 冻结成功后再开启第二段短事务，将任务正式交给后台 worker。
        return markUploadPending(confirmation);
    }

    /** 查询当前用户自己的上传任务，供客户端在收到 202 后轮询处理结果。 */
    public ResumeUploadStatusResponse getUploadStatus(UUID uploadId) {
        if (uploadId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Upload ID cannot be null");
        }
        Long currentUserId = currentUserProvider.getCurrentUserId();
        ResumeUploadSession session = uploadSessionRepository.findByIdAndUser_Id(uploadId, currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Upload task does not exist"));
        return new ResumeUploadStatusResponse(session.getId(), session.getStatus(),
                Optional.ofNullable(session.getAttemptCount()).orElse(0), session.getNextAttemptAt(), session.getResumeId(),
                session.getVersionNumber(), session.getLastErrorCode(), session.getLastErrorMessage(),
                session.getCreateTime(), session.getUpdateTime());
    }

    /**
     * 为已完成的 S3 简历生成临时下载地址。
     * 如果该版本不是通过 S3 上传的，
     * 返回 Optional.empty()，Controller 会尝试读取本地文件。
     * @param resumeId 简历ID
     * @return 如果当前用户拥有该简历，则返回临时下载URL，否则返回空
     */
    public Optional<String> createDownloadUrlIfPresent(Long resumeId, Integer versionNumber) {
        Long currentUserId = currentUserProvider.getCurrentUserId();

        // 根据resumeId，当前用户ID，上传状态和版本号来查询对应上传记录。
        return uploadSessionRepository
                .findCompletedVersion(resumeId, currentUserId, ResumeUploadStatus.COMPLETED, versionNumber)
                //如果查询到上传记录，获取对应S3正式对象Key。
                .map(
                    session ->
                            s3StorageService.createPresignedDownloadUrl(session.getFinalObjectKey(), Duration.ofMinutes(5))
                );
    }

    /** 在短事务中校验归属和状态，并提前保存处理对象 Key 作为崩溃恢复依据。 */
    private CompletePreparation prepareUploadConfirmation(UUID uploadId, Long currentUserId) {
        CompletePreparation preparation = transactionTemplate.execute(status -> {
            ResumeUploadSession session = findOwnedSessionForUpdate(uploadId, currentUserId);
            // 先处理幂等调用和终态，只有 UPLOADING 会继续执行 S3 冻结流程。
            ResumeUploadCompleteResponse existingResponse = resolveExistingCompleteResponse(session);
            if (existingResponse != null) {
                return new CompletePreparation(existingResponse, null);
            }
            Instant now = Instant.now();
            if (session.getExpiresAt().isBefore(now)) {
                session.setStatus(ResumeUploadStatus.EXPIRED);
                session.setNextAttemptAt(null);
                session.setUpdateTime(now);
                uploadSessionRepository.save(session);
                return new CompletePreparation(null, null);
            }
            // 每次重新上传使用新的随机处理 Key，避免复用 DEAD/EXPIRED 任务遗留的旧对象。
            String processingObjectKey = Optional.ofNullable(session.getProcessingObjectKey())
                    .orElseGet(() -> "%s/%d/%s/%s/source%s".formatted(processingPrefix, currentUserId, uploadId, UUID.randomUUID(),
                            session.getExpectedExtension()));
            // 复制前先落库：如果复制后进程崩溃，协调任务仍能根据这个 Key 找到已经生成的对象。
            session.setProcessingObjectKey(processingObjectKey);
            session.setUpdateTime(now);
            uploadSessionRepository.save(session);
            return new CompletePreparation(null, new UploadConfirmation(session.getId(), currentUserId, session.getUploadObjectKey(),
                    processingObjectKey, session.getExpectedContentType(), session.getExpectedSize()));
        });
        if (preparation == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to prepare upload confirmation");
        }
        if (preparation.response() == null && preparation.confirmation() == null) {
            throw uploadConflict("Upload session has expired");
        }
        return preparation;
    }

    /** 复制完成后在短事务中执行 UPLOADING 到 PENDING；并发重复请求直接复用当前任务结果。 */
    private ResumeUploadCompleteResponse markUploadPending(UploadConfirmation confirmation) {
        ResumeUploadCompleteResponse response = transactionTemplate.execute(status -> {
            // S3 调用期间没有持有数据库锁，因此入队前必须重新加锁并检查最新状态。
            ResumeUploadSession session = findOwnedSessionForUpdate(confirmation.uploadId(), confirmation.userId());
            ResumeUploadCompleteResponse existingResponse = resolveExistingCompleteResponse(session);
            if (existingResponse != null) {
                return existingResponse;
            }
            if (!Objects.equals(session.getProcessingObjectKey(), confirmation.processingObjectKey())) {
                throw uploadConflict("Upload processing object has changed");
            }
            session.setStatus(ResumeUploadStatus.PENDING);
            // 新入队任务可以立即被 worker 领取。
            session.setNextAttemptAt(Instant.now());
            session.setProcessingStartedAt(null);
            session.setClaimToken(null);
            session.setLastErrorCode(null);
            session.setLastErrorMessage(null);
            session.setUpdateTime(Instant.now());
            uploadSessionRepository.save(session);
            return toCompleteResponse(session);
        });
        if (response == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to enqueue uploaded resume");
        }
        return response;
    }

    /** PENDING、PROCESSING、COMPLETED 返回当前任务；DEAD、EXPIRED 返回 409；UPLOADING 继续确认。 */
    private ResumeUploadCompleteResponse resolveExistingCompleteResponse(ResumeUploadSession session) {
        return switch (session.getStatus()) {
            case PENDING, PROCESSING, COMPLETED -> toCompleteResponse(session);
            case DEAD -> throw uploadConflict("Upload task is dead and cannot be completed again");
            case EXPIRED -> throw uploadConflict("Upload session has expired");
            case UPLOADING -> null;
        };
    }

    private ResumeUploadSession findOwnedSessionForUpdate(UUID uploadId, Long currentUserId) {
        return uploadSessionRepository.findForUpdateByIdAndUserId(uploadId, currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Upload session does not exist"));
    }

    private ResumeUploadCompleteResponse toCompleteResponse(ResumeUploadSession session) {
        if (session.getStatus() == ResumeUploadStatus.COMPLETED
                && (session.getResumeId() == null || session.getVersionNumber() == null)) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Completed upload has no final result");
        }
        return new ResumeUploadCompleteResponse(session.getId(), session.getStatus(), session.getResumeId(), session.getVersionNumber());
    }

    private BusinessException uploadConflict(String description) {
        return new BusinessException(ErrorCode.UPLOAD_CONFLICT, description);
    }

    /**
     * 校验 S3 实际对象，而不是相信客户端的上传完成声明。
     */
    private void validateUploadedObject(UploadConfirmation confirmation, ResumeS3StorageService.StoredObjectMetadata metadata) {
        if (metadata.contentLength() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Uploaded file cannot be empty");
        }
        if (metadata.contentLength() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Uploaded file is too large");
        }
        if (metadata.contentLength() != confirmation.expectedSize()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Uploaded file size does not match the requested size");
        }
        if (!confirmation.expectedContentType().equalsIgnoreCase(metadata.contentType())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Uploaded object Content-Type does not match");
        }
    }

    private void validatePresignRequest(PresignResumeUploadRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Upload request cannot be null");
        }
        if (StringUtils.isBlank(request.resumeName())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume name cannot be blank");
        }
        if (request.resumeName().strip().length() > 256) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume name is too long");
        }
        if (StringUtils.isBlank(request.filename())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Filename cannot be blank");
        }
        resolveAllowedExtension(request.filename());
        if (request.fileSize() == null || request.fileSize() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "File size must be positive");
        }
        if (request.fileSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume file is too large");
        }
    }
    // 校验简历ID
    private void validateResumeId(Long resumeId) {
        if (resumeId == null || resumeId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Invalid resume ID");
        }
    }
    /**
     * 校验客户端提供的 Idempotency-Key。
     */
    private void validateIdempotencyKey(String idempotencyKey) {
        if (StringUtils.isBlank(idempotencyKey)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Idempotency-Key cannot be blank");
        }

        if (idempotencyKey.strip().length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Idempotency-Key is too long");
        }
    }

    private String resolveAllowedExtension(String filename) {
        String normalizedFilename = filename.toLowerCase(Locale.ROOT);
        if (normalizedFilename.endsWith(PDF_EXTENSION)) {
            return PDF_EXTENSION;
        }
        if (normalizedFilename.endsWith(DOCX_EXTENSION)) {
            return DOCX_EXTENSION;
        }
        throw new BusinessException(ErrorCode.PARAMS_ERROR, "Only PDF and DOCX resume files are supported");
    }

    private String resolveContentType(String extension) {
        if (PDF_EXTENSION.equals(extension)) {
            return PDF_CONTENT_TYPE;
        }
        if (DOCX_EXTENSION.equals(extension)) {
            return DOCX_CONTENT_TYPE;
        }
        throw new BusinessException(ErrorCode.PARAMS_ERROR, "Unsupported resume extension");
    }

    /** 保存快速完成阶段需要的不可变字段，避免 S3 调用期间持有数据库事务。 */
    private record UploadConfirmation(UUID uploadId, Long userId, String uploadObjectKey, String processingObjectKey,
                                      String expectedContentType, long expectedSize) {
    }

    /** 区分“直接复用已有结果”和“继续冻结上传对象”两种准备结果。 */
    private record CompletePreparation(ResumeUploadCompleteResponse response, UploadConfirmation confirmation) {
    }
}
