package com.keny.jobassistant.service;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.document.ResumeParseResult;
import com.keny.jobassistant.model.dto.PresignResumeUploadResponse;
import com.keny.jobassistant.model.entity.Resume;
import com.keny.jobassistant.model.entity.ResumeUploadSession;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.model.entity.request.PresignResumeUploadRequest;
import com.keny.jobassistant.model.enums.ResumeUploadStatus;
import com.keny.jobassistant.repository.ResumeRepository;
import com.keny.jobassistant.repository.ResumeUploadSessionRepository;
import com.keny.jobassistant.repository.UserRepository;
import com.keny.jobassistant.security.CurrentUserProvider;
import com.keny.jobassistant.service.support.PathBackedMultipartFile;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * S3 预签名简历上传业务服务。
 */
@Slf4j
@Service
public class ResumeS3UploadService {

    private static final int DEFAULT_RESUME_STATUS = 0;
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final String PDF_EXTENSION = ".pdf";
    private static final String DOCX_EXTENSION = ".docx";
    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String DOCX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ResumeS3StorageService s3StorageService;
    private final ResumeParserService resumeParserService;
    private final ResumeUploadSessionRepository uploadSessionRepository;
    private final ResumeRepository resumeRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final TransactionTemplate transactionTemplate;
    private final long presignDurationMinutes;
    private final String stagingPrefix;
    private final String finalPrefix;

    public ResumeS3UploadService(ResumeS3StorageService s3StorageService,
                                 ResumeParserService resumeParserService,
                                 ResumeUploadSessionRepository uploadSessionRepository,
                                 ResumeRepository resumeRepository,
                                 UserRepository userRepository,
                                 CurrentUserProvider currentUserProvider,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${app.resume.s3.presign-duration-minutes:5}") long presignDurationMinutes,
                                 @Value("${app.resume.s3.staging-prefix:resume-uploads}") String stagingPrefix,
                                 @Value("${app.resume.s3.final-prefix:resumes}") String finalPrefix) {
        this.s3StorageService = s3StorageService;
        this.resumeParserService = resumeParserService;
        this.uploadSessionRepository = uploadSessionRepository;
        this.resumeRepository = resumeRepository;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.presignDurationMinutes = presignDurationMinutes;
        this.stagingPrefix = stagingPrefix;
        this.finalPrefix = finalPrefix;
    }

    /**
     * 申请 S3 PUT 预签名 URL。
     * 该方法不会直接上传文件到 S3，而是完成上传前的准备工作：
     * 1. 校验用户提交的文件信息
     * 2. 创建临时 S3 对象 Key
     * 3. 生成短期 PUT 预签名 URL
     * 4. 保存上传会话 (upload session)
     * 5. 返回上传地址给客户端
     * @param request 上传请求信息，包括简历名称、文件名、文件大小
     * @return S3预签名上传地址以及上传相关信息
     */
    public PresignResumeUploadResponse createPresignedUpload(PresignResumeUploadRequest request) {
        validatePresignRequest(request); //基础参数校验
        Long currentUserId = currentUserProvider.getCurrentUserId();
        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_LOGIN));
        // 根据文件扩展名确定允许的文件类型
        String extension = resolveAllowedExtension(request.filename());
        // 根据扩展名确定上传Content-Type
        String contentType = resolveContentType(extension);
        // 创建唯一上传ID, UUID作为一次上传会话的唯一标识,后续接口里面的 POST /uploads/{uploadId}/complete
        UUID uploadId = UUID.randomUUID();
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
        session.setObjectKey(objectKey); //保存S3临时对象Key
        session.setExpectedExtension(extension); //这里的extension还没有被tika验证过，只是初步上传
        session.setExpectedContentType(contentType);
        session.setExpectedSize(request.fileSize());
        session.setStatus(ResumeUploadStatus.PENDING);
        session.setExpiresAt(result.expiresAt());
        session.setCreateTime(now);
        session.setUpdateTime(now);
        uploadSessionRepository.save(session);
        return new PresignResumeUploadResponse(uploadId, result.uploadUrl(), result.expiresAt(), result.requiredHeaders());
    }

    /**
     * 确认上传完成。
     * 1. 校验上传会话归属
     * 2. 检查S3对象是否存在
     * 3. 校验文件大小、类型
     * 4. 下载文件到临时目录
     * 5. 调用已有Tika + AI解析流程
     * 6. 上传验证后的文件到正式S3目录
     * 7. 创建Resume记录
     * 8. 删除staging临时对象
     * @param uploadId 上传会话ID
     * @return 创建成功的Resume ID
     */
    public Long completeUpload(UUID uploadId) {
        if (uploadId == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Upload ID cannot be null");
        }
        Long currentUserId = currentUserProvider.getCurrentUserId();
        // 查询上传会话，并进行权限校验
        // 返回UploadClaim包含：用户信息，S3 staging Key，文件名，文件扩展名，上传状态，已创建Resume ID
        UploadClaim claim = claimUpload(uploadId, currentUserId);

        // 重复调用已完成的确认接口时，直接返回原 Resume ID，保证幂等。
        //complete接口可能被重复调用
        //第一次：上传成功，创建Resume(id=13)
        //第二次：同一个uploadId再次complete
        //此时不能重复创建Resume，直接返回之前创建的Resume ID。
        if (claim.completedResumeId() != null) {
            return claim.completedResumeId();
        }

        Path temporaryFile = null; //本地临时文件路径。
        String finalObjectKey = null; //正式S3对象Key(路径)

        try {
            //查询S3对象元数据
            ResumeS3StorageService.StoredObjectMetadata metadata = s3StorageService.getObjectMetadata(claim.stagingObjectKey());
            validateUploadedObject(claim, metadata);
            temporaryFile = Files.createTempFile("resume-s3-", claim.expectedExtension());
            s3StorageService.downloadObject(claim.stagingObjectKey(), temporaryFile);// 从S3 staging目录下载文件
            // 再次校验下载后的文件大小 防止S3文件大小!=本地临时文件大小，从而避免解析不完整文件。
            if (Files.size(temporaryFile) != metadata.contentLength()) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "Downloaded file size does not match S3 metadata");
            }

            // 复用现有 Tika + AI 流程。解析服务仍然通过 InputStream 读取
            // 创建MultipartFile适配器。原来的解析流程： MultipartFile-->Tika-->AI,
            // 现在文件来自S3，所以包装成本地Path对应的MultipartFile
            MultipartFile multipartFile = new PathBackedMultipartFile(
                    "file",
                    claim.originalFilename(),
                    claim.expectedContentType(),
                    temporaryFile
            );
            //调用已有简历解析流程
            ResumeParseResult parseResult = resumeParserService.parseResume(multipartFile);
            // 二次确认文件类型,这次是tika检验后的了
            if (!claim.expectedExtension().equals(parseResult.extension())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "Uploaded file type does not match the requested extension");
            }

            //创建正式S3 Key
            //staging: 用户上传入口，不可信   final: 已经过验证，可以长期保存
            finalObjectKey = "%s/%d/%s/source%s".formatted(finalPrefix, currentUserId, uploadId, parseResult.extension());
            s3StorageService.uploadValidatedObject(finalObjectKey, temporaryFile, resolveContentType(parseResult.extension()));
            Long resumeId = saveCompletedResume(claim, parseResult, finalObjectKey); //保存业务数据

            // 正式文件已经保存，删除临时上传对象。
            s3StorageService.deleteObjectQuietly(claim.stagingObjectKey());
            return resumeId;
        } catch (BusinessException exception) {
            //业务异常
            markUploadFailed(uploadId, currentUserId);
            s3StorageService.deleteObjectQuietly(claim.stagingObjectKey());
            s3StorageService.deleteObjectQuietly(finalObjectKey);
            throw exception;
        } catch (IOException exception) {
            //本地文件操作异常
            markUploadFailed(uploadId, currentUserId);
            s3StorageService.deleteObjectQuietly(claim.stagingObjectKey());
            s3StorageService.deleteObjectQuietly(finalObjectKey);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to process temporary resume file");
        } catch (RuntimeException exception) {
            markUploadFailed(uploadId, currentUserId);
            s3StorageService.deleteObjectQuietly(claim.stagingObjectKey());
            s3StorageService.deleteObjectQuietly(finalObjectKey);
            log.error("Unexpected S3 resume processing failure, uploadId={}", uploadId, exception);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to complete resume upload");
        } finally {
            deleteTemporaryFileQuietly(temporaryFile);//无论成功还是失败，都删除本地临时文件
        }
    }

    /**
     * 为已完成的 S3 简历生成临时下载地址。
     * @param resumeId 简历ID
     * @return 如果当前用户拥有该简历，则返回临时下载URL，否则返回空
     */
    public Optional<String> createDownloadUrlIfPresent(Long resumeId) {
        Long currentUserId = currentUserProvider.getCurrentUserId();

        // 根据resumeId，当前用户ID，上传状态来查询对应上传记录。
        return uploadSessionRepository
                .findByResumeIdAndUser_IdAndStatus(resumeId, currentUserId, ResumeUploadStatus.COMPLETED)
                //如果查询到上传记录，获取对应S3正式对象Key。
                .map(session -> s3StorageService.createPresignedDownloadUrl(session.getObjectKey(), Duration.ofMinutes(5)));
    }

    /**
     * 在短事务中锁定上传会话并切换为 PROCESSING。
     * 该方法负责 completeUpload 的第一阶段：
     *  1. 查询当前用户对应的上传会话
     *  2. 使用数据库行锁防止重复处理
     *  3. 检查上传状态是否合法
     *  4. 将 PENDING 状态修改为 PROCESSING
     */
    private UploadClaim claimUpload(UUID uploadId, Long currentUserId) {
        //查询上传会话并加行锁.当前事务修改该记录期间，其他事务不能同时修改。
        UploadClaim claim = transactionTemplate.execute(status -> {
            ResumeUploadSession session = uploadSessionRepository.findForUpdateByIdAndUserId(uploadId, currentUserId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Upload session does not exist"));
            // 如果之前已经完成,那么再次调用complete接口：不重新解析文件,不重新创建Resume-->直接返回之前生成的Resume ID
            if (session.getStatus() == ResumeUploadStatus.COMPLETED) {
                return new UploadClaim(session.getResumeId(), session.getId(), currentUserId, session.getResumeName(),
                        session.getOriginalFilename(), session.getObjectKey(), session.getExpectedExtension(),
                        session.getExpectedContentType(), session.getExpectedSize());
            }
            if (session.getExpiresAt().isBefore(Instant.now())) { //检查上传会话是否过期
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "Upload session has expired");
            }
            if (session.getStatus() != ResumeUploadStatus.PENDING) { //不是Pending不允许继续处理
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "Upload session is not pending");
            }
            session.setStatus(ResumeUploadStatus.PROCESSING);
            //抢占上传任务。PROCESSING表示：当前线程已经获得处理权
            //后续开始 S3检查，文件下载，Tika解析，AI解析
            session.setUpdateTime(Instant.now());
            uploadSessionRepository.save(session);
            return new UploadClaim(null, session.getId(), currentUserId, session.getResumeName(),
                    session.getOriginalFilename(), session.getObjectKey(), session.getExpectedExtension(),
                    session.getExpectedContentType(), session.getExpectedSize());
        });
        if (claim == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to claim upload session");
        }
        return claim;
    }

    /**
     * 校验 S3 实际对象，而不是相信客户端的上传完成声明。
     */
    private void validateUploadedObject(UploadClaim claim, ResumeS3StorageService.StoredObjectMetadata metadata) {
        if (metadata.contentLength() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Uploaded file cannot be empty");
        }
        if (metadata.contentLength() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Uploaded file is too large");
        }
        if (metadata.contentLength() != claim.expectedSize()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Uploaded file size does not match the requested size");
        }
        if (!claim.expectedContentType().equalsIgnoreCase(metadata.contentType())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Uploaded object Content-Type does not match");
        }
    }

    /**
     * 创建 Resume，并将上传会话切换为 COMPLETED。
     * 1. 创建 Resume 业务数据
     * 2. 保存 AI 解析结果
     * 3. 更新正式文件访问地址
     * 4. 更新上传会话状态
     * 只有当：文件已经通过 Tika 校验，AI解析成功，正式S3对象上传成功后才会进入该方法。
     * @param claim 上传会话信息
     * @param parseResult AI解析后的简历结果
     * @param finalObjectKey 正式S3对象Key
     * @return 创建成功的Resume ID
     */
    private Long saveCompletedResume(UploadClaim claim, ResumeParseResult parseResult, String finalObjectKey) {
        //使用事务执行整个数据库操作
        Long resumeId = transactionTemplate.execute(status -> { //查询上传会话，并加数据库行锁。
            //防止多个请求同时complete同一个uploadId
            ResumeUploadSession session = uploadSessionRepository.findForUpdateByIdAndUserId(claim.uploadId(), claim.userId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Upload session does not exist"));
            // 这里只允许PROCESSING状态进入COMPLETED,防止已失败的上传或者已经完成的上传被重复处理
            if (session.getStatus() != ResumeUploadStatus.PROCESSING) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "Upload session is not processing");
            }

            User currentUser = userRepository.findById(claim.userId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_LOGIN));

            LocalDateTime now = LocalDateTime.now();
            Resume resume = new Resume();
            resume.setUser(currentUser);
            resume.setResumeName(claim.resumeName());
            resume.setFileUrl(null);
            resume.setParsedJson(parseResult.parsedJson());
            resume.setStatus(DEFAULT_RESUME_STATUS);
            resume.setCreateTime(now);
            resume.setUpdateTime(now);

            Resume savedResume = resumeRepository.saveAndFlush(resume);
            savedResume.setFileUrl("/resumes/" + savedResume.getId() + "/file");
            savedResume.setUpdateTime(LocalDateTime.now());
            resumeRepository.save(savedResume);

            session.setObjectKey(finalObjectKey);//完成了就改成最终的目录
            session.setResumeId(savedResume.getId());
            session.setStatus(ResumeUploadStatus.COMPLETED);
            session.setUpdateTime(Instant.now());
            uploadSessionRepository.save(session);

            return savedResume.getId();
        });
        if (resumeId == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to create resume");
        }
        return resumeId;
    }
    //当简历上传流程失败时，把上传会话状态从 PROCESSING 标记为 FAILED
    private void markUploadFailed(UUID uploadId, Long currentUserId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                ResumeUploadSession session = uploadSessionRepository.findForUpdateByIdAndUserId(uploadId, currentUserId).orElse(null);
                //这个代码是放在catch部分的，数据库里还能找到这次上传，并且它正处于正在处理的中间状态，
                //那么说明这次任务已经开始但没有成功完成，因此把它记录为失败
                if (session != null && session.getStatus() == ResumeUploadStatus.PROCESSING) {
                    session.setStatus(ResumeUploadStatus.FAILED);
                    session.setUpdateTime(Instant.now());
                    uploadSessionRepository.save(session);
                }
            });
        } catch (RuntimeException exception) {
            log.warn("Failed to mark upload session as failed, uploadId={}", uploadId, exception);
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

    private void deleteTemporaryFileQuietly(Path temporaryFile) {
        if (temporaryFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException exception) {
            log.warn("Failed to delete temporary resume file, path={}", temporaryFile, exception);
        }
    }

    private record UploadClaim(
            Long completedResumeId,
            UUID uploadId,
            Long userId,
            String resumeName,
            String originalFilename,
            String stagingObjectKey,
            String expectedExtension,
            String expectedContentType,
            long expectedSize
    ) {
    }
}