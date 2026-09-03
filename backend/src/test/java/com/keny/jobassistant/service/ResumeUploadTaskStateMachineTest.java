package com.keny.jobassistant.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.controller.ResumeUploadController;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.document.ResumeParseResult;
import com.keny.jobassistant.model.dto.PresignResumeUploadResponse;
import com.keny.jobassistant.model.dto.ResumeUploadCompleteResponse;
import com.keny.jobassistant.model.dto.ResumeUploadStatusResponse;
import com.keny.jobassistant.model.entity.Resume;
import com.keny.jobassistant.model.entity.ResumeUploadSession;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.model.entity.request.PresignResumeUploadRequest;
import com.keny.jobassistant.model.enums.ResumeUploadStatus;
import com.keny.jobassistant.model.enums.ResumeUploadType;
import com.keny.jobassistant.model.task.ResumeUploadTaskClaim;
import com.keny.jobassistant.repository.ResumeRepository;
import com.keny.jobassistant.repository.ResumeUploadSessionRepository;
import com.keny.jobassistant.repository.ResumeUploadTaskClaimRepository;
import com.keny.jobassistant.repository.ResumeVersionAtomicRepository;
import com.keny.jobassistant.repository.UserRepository;
import com.keny.jobassistant.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 集中验证异步上传从预签名、快速完成、后台处理到异常恢复的关键行为。 */
@ExtendWith(MockitoExtension.class)
class ResumeUploadTaskStateMachineTest {

    private static final Long USER_ID = 7L;
    private static final String PDF_CONTENT_TYPE = "application/pdf";
    private static final String ETAG = "\"source-etag\"";

    @Mock
    private ResumeS3StorageService s3StorageService;
    @Mock
    private ResumeUploadSessionRepository uploadSessionRepository;
    @Mock
    private ResumeUploadTaskClaimRepository taskClaimRepository;
    @Mock
    private ResumeParserService resumeParserService;
    @Mock
    private ResumeRepository resumeRepository;
    @Mock
    private ResumeVersionAtomicRepository resumeVersionAtomicRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;
    @Mock
    private S3Client s3Client;
    @Mock
    private S3Presigner s3Presigner;
    @Mock
    private ResumeS3UploadService controllerUploadService;

    private ResumeS3UploadService uploadService;
    private User user;

    /** 每个测试都使用相同的用户和上传目录配置，避免重复搭建无关数据。 */
    @BeforeEach
    void setUp() {
        uploadService = new ResumeS3UploadService(s3StorageService, uploadSessionRepository, resumeRepository, userRepository,
                currentUserProvider, transactionManager, 5, "resume-uploads", "resume-processing");
        user = new User();
        user.setId(USER_ID);
    }

    /** 新幂等键必须创建一条 UPLOADING 任务，并把该键写入上传会话。 */
    @Test
    void presignShouldCreateUploadingTaskWithIdempotencyKey() {
        when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(uploadSessionRepository.findCreateByIdempotencyKey(USER_ID, ResumeUploadType.CREATE, "create-key"))
                .thenReturn(Optional.empty());
        when(s3StorageService.createPresignedUpload(anyString(), eq(PDF_CONTENT_TYPE), any()))
                .thenReturn(presignedUpload());

        uploadService.createPresignedUpload("create-key", uploadRequest());

        ArgumentCaptor<ResumeUploadSession> sessionCaptor = ArgumentCaptor.forClass(ResumeUploadSession.class);
        verify(uploadSessionRepository).saveAndFlush(sessionCaptor.capture());
        assertThat(sessionCaptor.getValue().getStatus()).isEqualTo(ResumeUploadStatus.UPLOADING);
        assertThat(sessionCaptor.getValue().getIdempotencyKey()).isEqualTo("create-key");
        assertThat(sessionCaptor.getValue().getProcessingObjectKey()).isNull();
    }

    /** CREATE 和 NEW_VERSION 使用相同幂等键重试时都必须复用第一次生成的 uploadId。 */
    @ParameterizedTest
    @EnumSource(value = ResumeUploadType.class, names = {"CREATE", "NEW_VERSION"})
    void repeatedPresignShouldReuseTaskForBothUploadTypes(ResumeUploadType uploadType) {
        String idempotencyKey = "same-request-key";
        ResumeUploadSession session = newUploadSession(ResumeUploadStatus.UPLOADING, Instant.now().plusSeconds(300));
        session.setUploadType(uploadType);
        session.setResumeId(uploadType == ResumeUploadType.NEW_VERSION ? 55L : null);
        session.setIdempotencyKey(idempotencyKey);
        when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(s3StorageService.createPresignedUpload(session.getUploadObjectKey(), PDF_CONTENT_TYPE, java.time.Duration.ofMinutes(5)))
                .thenReturn(presignedUpload());
        if (uploadType == ResumeUploadType.CREATE) {
            when(uploadSessionRepository.findCreateByIdempotencyKey(USER_ID, uploadType, idempotencyKey))
                    .thenReturn(Optional.of(session));
        } else {
            when(resumeRepository.existsByIdAndUser_IdAndIsDelete(55L, USER_ID, Resume.NOT_DELETED)).thenReturn(true);
            when(uploadSessionRepository.findByIdempotencyKey(USER_ID, 55L, uploadType, idempotencyKey))
                    .thenReturn(Optional.of(session));
        }

        PresignResumeUploadResponse response = uploadType == ResumeUploadType.CREATE
                ? uploadService.createPresignedUpload(idempotencyKey, uploadRequest())
                : uploadService.createPresignedVersionUpload(55L, idempotencyKey, uploadRequest());

        assertThat(response.uploadId()).isEqualTo(session.getId());
        verify(uploadSessionRepository).save(session);
        verify(uploadSessionRepository, never()).saveAndFlush(any());
    }

    /** 状态轮询必须返回任务重试信息，且只能按当前用户查询。 */
    @Test
    void statusPollingShouldReturnOwnedTaskDetails() {
        when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
        ResumeUploadSession session = newUploadSession(ResumeUploadStatus.PENDING, Instant.now().plusSeconds(300));
        session.setAttemptCount(1);
        session.setLastErrorCode("50000");
        session.setLastErrorMessage("Temporary processing failure");
        when(uploadSessionRepository.findByIdAndUser_Id(session.getId(), USER_ID)).thenReturn(Optional.of(session));

        ResumeUploadStatusResponse response = uploadService.getUploadStatus(session.getId());

        assertThat(response.uploadId()).isEqualTo(session.getId());
        assertThat(response.status()).isEqualTo(ResumeUploadStatus.PENDING);
        assertThat(response.attemptCount()).isEqualTo(1);
        assertThat(response.lastErrorCode()).isEqualTo("50000");
    }

    /** complete 只校验元数据、冻结文件并入队，不能在请求线程调用解析器或创建简历。 */
    @Test
    void completeShouldFreezeAndQueueWithoutParsing() {
        ResumeUploadSession session = prepareOwnedSession(ResumeUploadStatus.UPLOADING, Instant.now().plusSeconds(300));
        when(s3StorageService.findObjectMetadata(anyString())).thenReturn(Optional.empty());
        when(s3StorageService.getObjectMetadata(session.getUploadObjectKey()))
                .thenReturn(new ResumeS3StorageService.StoredObjectMetadata(4L, PDF_CONTENT_TYPE, ETAG));

        ResumeUploadCompleteResponse response = uploadService.completeUpload(session.getId());

        assertThat(response.status()).isEqualTo(ResumeUploadStatus.PENDING);
        assertThat(response.resumeId()).isNull();
        assertThat(session.getProcessingObjectKey()).startsWith("resume-processing/7/" + session.getId() + "/");
        verify(s3StorageService).copyObjectIfMatch(session.getUploadObjectKey(), session.getProcessingObjectKey(), ETAG);
        verify(s3StorageService, never()).downloadObject(anyString(), any());
        verifyNoInteractions(resumeParserService, resumeVersionAtomicRepository);
    }

    /** 文件大小或 Content-Type 不匹配时必须在复制之前拒绝请求。 */
    @ParameterizedTest
    @CsvSource({"5,application/pdf", "4,text/plain"})
    void completeShouldRejectMismatchedMetadata(long actualSize, String actualContentType) {
        ResumeUploadSession session = prepareOwnedSession(ResumeUploadStatus.UPLOADING, Instant.now().plusSeconds(300));
        when(s3StorageService.findObjectMetadata(anyString())).thenReturn(Optional.empty());
        when(s3StorageService.getObjectMetadata(session.getUploadObjectKey()))
                .thenReturn(new ResumeS3StorageService.StoredObjectMetadata(actualSize, actualContentType, ETAG));

        assertThatThrownBy(() -> uploadService.completeUpload(session.getId()))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.PARAMS_ERROR.getCode()));

        assertThat(session.getStatus()).isEqualTo(ResumeUploadStatus.UPLOADING);
        verify(s3StorageService, never()).copyObjectIfMatch(anyString(), anyString(), anyString());
    }

    /** 活跃任务和已完成任务重复调用 complete 时必须直接返回同一任务结果。 */
    @ParameterizedTest
    @EnumSource(value = ResumeUploadStatus.class, names = {"PENDING", "PROCESSING", "COMPLETED"})
    void repeatedCompleteShouldReturnExistingTask(ResumeUploadStatus status) {
        ResumeUploadSession session = prepareOwnedSession(status, Instant.now().plusSeconds(300));
        if (status == ResumeUploadStatus.COMPLETED) {
            session.setResumeId(99L);
            session.setVersionNumber(3);
        }

        ResumeUploadCompleteResponse response = uploadService.completeUpload(session.getId());

        assertThat(response.uploadId()).isEqualTo(session.getId());
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.resumeId()).isEqualTo(session.getResumeId());
        verifyNoInteractions(s3StorageService);
    }

    /** DEAD 和 EXPIRED 是不可继续确认的终态，重复调用必须返回 HTTP 409 对应异常。 */
    @ParameterizedTest
    @EnumSource(value = ResumeUploadStatus.class, names = {"DEAD", "EXPIRED"})
    void terminalCompleteShouldReturnConflict(ResumeUploadStatus status) {
        ResumeUploadSession session = prepareOwnedSession(status, Instant.now().plusSeconds(300));

        assertThatThrownBy(() -> uploadService.completeUpload(session.getId()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.UPLOAD_CONFLICT.getCode());
                    assertThat(exception.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verifyNoInteractions(s3StorageService);
    }

    /** Controller 应按任务是否完成分别返回 202 或 200。 */
    @ParameterizedTest
    @CsvSource({"PENDING,202", "PROCESSING,202", "COMPLETED,200"})
    void completeEndpointShouldMapTaskStatusToHttpStatus(ResumeUploadStatus status, int expectedStatus) {
        UUID uploadId = UUID.randomUUID();
        ResumeUploadCompleteResponse serviceResponse = new ResumeUploadCompleteResponse(uploadId, status,
                status == ResumeUploadStatus.COMPLETED ? 99L : null, status == ResumeUploadStatus.COMPLETED ? 1 : null);
        when(controllerUploadService.completeUpload(uploadId)).thenReturn(serviceResponse);

        ResponseEntity<BaseResponse<ResumeUploadCompleteResponse>> response = new ResumeUploadController(controllerUploadService).completeUpload(uploadId);

        assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData()).isEqualTo(serviceResponse);
    }

    /** presign 缺少 Idempotency-Key 时必须在进入业务服务前返回 400。 */
    @Test
    void presignEndpointShouldRequireIdempotencyKey() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ResumeUploadController(controllerUploadService)).build();

        mockMvc.perform(post("/resumes/uploads/presign")
                        .contentType("application/json")
                        .content("{\"resumeName\":\"Backend Resume\",\"filename\":\"resume.pdf\",\"fileSize\":4}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(controllerUploadService);
    }

    /** S3 冻结复制必须同时约束源 ETag 和目标对象不存在。 */
    @Test
    void frozenCopyShouldUseSourceEtagAndMissingTargetConditions() {
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());
        ResumeS3StorageService storageService = new ResumeS3StorageService(s3Client, s3Presigner, "test-bucket");

        storageService.copyObjectIfMatch("resume-uploads/7/upload/source.pdf", "resume-processing/7/upload/source.pdf", ETAG);

        ArgumentCaptor<CopyObjectRequest> requestCaptor = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(requestCaptor.capture());
        CopyObjectRequest request = requestCaptor.getValue();
        assertThat(request.copySourceIfMatch()).isEqualTo(ETAG);
        assertThat(request.ifNoneMatch()).isEqualTo("*");
        assertThat(request.metadataDirectiveAsString()).isEqualTo("COPY");
        assertThat(request.key()).isEqualTo("resume-processing/7/upload/source.pdf");
    }

    /** ETag 变化或目标已存在导致的 412 必须转换成上传冲突。 */
    @Test
    void frozenCopyPreconditionFailureShouldReturnConflict() {
        when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(412).message("changed").build());
        ResumeS3StorageService storageService = new ResumeS3StorageService(s3Client, s3Presigner, "test-bucket");

        assertThatThrownBy(() -> storageService.copyObjectIfMatch("resume-uploads/source.pdf", "resume-processing/source.pdf", ETAG))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ErrorCode.UPLOAD_CONFLICT.getCode()));
    }

    /** CREATE worker 成功后必须创建简历首个版本、保存正式对象并清理临时对象。 */
    @Test
    void workerShouldCompleteCreateTaskAndCleanTemporaryObjects() throws Exception {
        enableTransactions();
        ResumeUploadTaskClaim claim = taskClaim(ResumeUploadType.CREATE, null, 1);
        ResumeUploadSession session = processingSession(claim);
        when(taskClaimRepository.claimNext(any(UUID.class))).thenReturn(Optional.of(claim));
        stubDownloadedPdf(claim);
        when(uploadSessionRepository.findForUpdateById(claim.uploadId())).thenReturn(Optional.of(session));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(resumeRepository.saveAndFlush(any(Resume.class))).thenAnswer(invocation -> {
            Resume resume = invocation.getArgument(0);
            resume.setId(99L);
            return resume;
        });
        when(resumeVersionAtomicRepository.createNextVersion(eq(99L), eq(USER_ID), eq("Backend Resume"),
                eq("/resumes/99/file"), any())).thenReturn(Optional.of(1));

        newWorker().processPendingUploads();

        assertThat(session.getStatus()).isEqualTo(ResumeUploadStatus.COMPLETED);
        assertThat(session.getResumeId()).isEqualTo(99L);
        assertThat(session.getVersionNumber()).isEqualTo(1);
        verify(resumeParserService).parseResume(any(MultipartFile.class));
        verify(s3StorageService).deleteObjectQuietly(claim.processingObjectKey());
        verify(s3StorageService).deleteObjectQuietly(claim.uploadObjectKey());
    }

    /** NEW_VERSION worker 只能给原简历新增版本，不能重复创建 Resume 主记录。 */
    @Test
    void workerShouldCompleteNewVersionWithoutCreatingResume() throws Exception {
        enableTransactions();
        ResumeUploadTaskClaim claim = taskClaim(ResumeUploadType.NEW_VERSION, 55L, 1);
        ResumeUploadSession session = processingSession(claim);
        when(taskClaimRepository.claimNext(any(UUID.class))).thenReturn(Optional.of(claim));
        stubDownloadedPdf(claim);
        when(uploadSessionRepository.findForUpdateById(claim.uploadId())).thenReturn(Optional.of(session));
        when(resumeVersionAtomicRepository.createNextVersion(eq(55L), eq(USER_ID), eq("Backend Resume"),
                eq("/resumes/55/file"), any())).thenReturn(Optional.of(4));

        newWorker().processPendingUploads();

        assertThat(session.getStatus()).isEqualTo(ResumeUploadStatus.COMPLETED);
        assertThat(session.getResumeId()).isEqualTo(55L);
        assertThat(session.getVersionNumber()).isEqualTo(4);
        verifyNoInteractions(userRepository, resumeRepository);
    }

    /** 临时错误应按次数进入退避重试或 DEAD，并且只有 DEAD 才清理临时对象。 */
    @ParameterizedTest
    @CsvSource({"1,PENDING,false", "3,DEAD,true"})
    void workerFailureShouldApplyBackoffOrDeadLetter(int attemptCount, ResumeUploadStatus expectedStatus, boolean shouldClean) {
        enableTransactions();
        ResumeUploadTaskClaim claim = taskClaim(ResumeUploadType.CREATE, null, attemptCount);
        ResumeUploadSession session = processingSession(claim);
        when(taskClaimRepository.claimNext(any(UUID.class))).thenReturn(Optional.of(claim));
        doThrow(new BusinessException(ErrorCode.SYSTEM_ERROR, "S3 temporary failure"))
                .when(s3StorageService).downloadObject(eq(claim.processingObjectKey()), any());
        when(uploadSessionRepository.findForUpdateById(claim.uploadId())).thenReturn(Optional.of(session));

        newWorker().processPendingUploads();

        assertThat(session.getStatus()).isEqualTo(expectedStatus);
        assertThat(session.getClaimToken()).isNull();
        if (shouldClean) {
            assertThat(session.getNextAttemptAt()).isNull();
            verify(s3StorageService).deleteObjectQuietly(claim.processingObjectKey());
            verify(s3StorageService).deleteObjectQuietly(claim.uploadObjectKey());
        } else {
            assertThat(session.getNextAttemptAt()).isAfter(Instant.now());
            verify(s3StorageService, never()).deleteObjectQuietly(claim.processingObjectKey());
        }
    }

    /** 旧 worker 的 claim token 已失效时不能覆盖新 worker 持有的任务状态。 */
    @Test
    void staleWorkerShouldBeBlockedByClaimToken() throws Exception {
        enableTransactions();
        ResumeUploadTaskClaim claim = taskClaim(ResumeUploadType.NEW_VERSION, 55L, 1);
        ResumeUploadSession session = processingSession(claim);
        UUID newerClaimToken = UUID.randomUUID();
        session.setClaimToken(newerClaimToken);
        when(taskClaimRepository.claimNext(any(UUID.class))).thenReturn(Optional.of(claim));
        stubDownloadedPdf(claim);
        when(uploadSessionRepository.findForUpdateById(claim.uploadId())).thenReturn(Optional.of(session));

        newWorker().processPendingUploads();

        assertThat(session.getStatus()).isEqualTo(ResumeUploadStatus.PROCESSING);
        assertThat(session.getClaimToken()).isEqualTo(newerClaimToken);
        verify(uploadSessionRepository, never()).save(session);
        verifyNoInteractions(resumeVersionAtomicRepository);
    }

    /** 已复制但未入队的对象必须由协调器恢复为 PENDING。 */
    @Test
    void recoveryShouldQueueInterruptedFrozenCopy() {
        ResumeUploadSession session = recoveryCandidate();
        when(uploadSessionRepository.findFreezeRecoveryCandidates(eq(ResumeUploadStatus.UPLOADING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(session));
        when(s3StorageService.findObjectMetadata(session.getProcessingObjectKey()))
                .thenReturn(Optional.of(new ResumeS3StorageService.StoredObjectMetadata(4L, PDF_CONTENT_TYPE, ETAG)));
        enableTransactions();
        when(uploadSessionRepository.findForUpdateById(session.getId())).thenReturn(Optional.of(session));

        newCoordinator().recoverCopiedUploads();

        assertThat(session.getStatus()).isEqualTo(ResumeUploadStatus.PENDING);
        assertThat(session.getNextAttemptAt()).isNotNull();
        verify(uploadSessionRepository).save(session);
    }

    /** 超时任务按已尝试次数重新入队或转入 DEAD，并清除旧 claim token。 */
    @ParameterizedTest
    @CsvSource({"1,PENDING", "3,DEAD"})
    void recoveryShouldHandleTimedOutProcessingTask(int attemptCount, ResumeUploadStatus expectedStatus) {
        ResumeUploadSession session = timedOutProcessingSession(attemptCount);
        when(uploadSessionRepository.findProcessingTimeoutCandidates(eq(ResumeUploadStatus.PROCESSING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(session));
        enableTransactions();
        when(uploadSessionRepository.findForUpdateById(session.getId())).thenReturn(Optional.of(session));

        newCoordinator().recoverCopiedUploads();

        assertThat(session.getStatus()).isEqualTo(expectedStatus);
        assertThat(session.getClaimToken()).isNull();
        assertThat(session.getProcessingStartedAt()).isNull();
        assertThat(session.getLastErrorCode()).isEqualTo("PROCESSING_TIMEOUT");
    }

    /** 让 TransactionTemplate 使用 Mockito 提供的轻量事务。 */
    private void enableTransactions() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
    }

    /** 构造属于当前用户的会话并配置带锁查询。 */
    private ResumeUploadSession prepareOwnedSession(ResumeUploadStatus status, Instant expiresAt) {
        enableTransactions();
        when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
        ResumeUploadSession session = newUploadSession(status, expiresAt);
        when(uploadSessionRepository.findForUpdateByIdAndUserId(session.getId(), USER_ID)).thenReturn(Optional.of(session));
        return session;
    }

    /** 构造包含上传流程必要字段的会话。 */
    private ResumeUploadSession newUploadSession(ResumeUploadStatus status, Instant expiresAt) {
        Instant now = Instant.now();
        ResumeUploadSession session = new ResumeUploadSession();
        session.setId(UUID.randomUUID());
        session.setUser(user);
        session.setResumeName("Backend Resume");
        session.setOriginalFilename("resume.pdf");
        session.setUploadObjectKey("resume-uploads/7/" + session.getId() + "/source.pdf");
        session.setExpectedExtension(".pdf");
        session.setExpectedContentType(PDF_CONTENT_TYPE);
        session.setExpectedSize(4L);
        session.setStatus(status);
        session.setUploadType(ResumeUploadType.CREATE);
        session.setAttemptCount(0);
        session.setExpiresAt(expiresAt);
        session.setCreateTime(now);
        session.setUpdateTime(now);
        return session;
    }

    /** 构造 S3 预签名结果。 */
    private ResumeS3StorageService.PresignedUploadResult presignedUpload() {
        return new ResumeS3StorageService.PresignedUploadResult("https://example.test/upload",
                Instant.now().plusSeconds(300), Map.of("Content-Type", PDF_CONTENT_TYPE));
    }

    /** 构造预签名接口请求。 */
    private PresignResumeUploadRequest uploadRequest() {
        return new PresignResumeUploadRequest("Backend Resume", "resume.pdf", 4L);
    }

    /** 构造 worker 领取后的不可变任务快照。 */
    private ResumeUploadTaskClaim taskClaim(ResumeUploadType uploadType, Long targetResumeId, int attemptCount) {
        UUID uploadId = UUID.randomUUID();
        return new ResumeUploadTaskClaim(uploadId, UUID.randomUUID(), attemptCount, USER_ID, uploadType,
                targetResumeId, "Backend Resume", "resume.pdf", "resume-uploads/7/" + uploadId + "/source.pdf",
                "resume-processing/7/" + uploadId + "/frozen/source.pdf", ".pdf", PDF_CONTENT_TYPE, 4L);
    }

    /** 构造与当前 claim token 对应的 PROCESSING 会话。 */
    private ResumeUploadSession processingSession(ResumeUploadTaskClaim claim) {
        ResumeUploadSession session = new ResumeUploadSession();
        session.setId(claim.uploadId());
        session.setStatus(ResumeUploadStatus.PROCESSING);
        session.setAttemptCount(claim.attemptCount());
        session.setClaimToken(claim.claimToken());
        session.setProcessingStartedAt(Instant.now());
        return session;
    }

    /** 模拟 worker 下载四字节 PDF 并由解析器返回已验证结果。 */
    private void stubDownloadedPdf(ResumeUploadTaskClaim claim) throws Exception {
        doAnswer(invocation -> {
            Files.write(invocation.getArgument(1), new byte[]{1, 2, 3, 4});
            return null;
        }).when(s3StorageService).downloadObject(eq(claim.processingObjectKey()), any());
        when(resumeParserService.parseResume(any(MultipartFile.class))).thenReturn(new ResumeParseResult(
                JsonNodeFactory.instance.objectNode().put("name", "Keny"), PDF_CONTENT_TYPE, ".pdf"));
    }

    /** 创建只处理一个任务的 worker，便于精确断言单次状态变化。 */
    private ResumeUploadWorker newWorker() {
        return new ResumeUploadWorker(taskClaimRepository, uploadSessionRepository, s3StorageService,
                resumeParserService, resumeRepository, resumeVersionAtomicRepository, userRepository,
                transactionManager, "resumes", 1, 3, 30, 600);
    }

    /** 创建使用短超时配置的恢复协调器。 */
    private ResumeUploadRecoveryCoordinator newCoordinator() {
        return new ResumeUploadRecoveryCoordinator(uploadSessionRepository, s3StorageService, transactionManager, 1, 1, 10, 3);
    }

    /** 构造复制成功但尚未入队的恢复候选。 */
    private ResumeUploadSession recoveryCandidate() {
        ResumeUploadSession session = new ResumeUploadSession();
        session.setId(UUID.randomUUID());
        session.setStatus(ResumeUploadStatus.UPLOADING);
        session.setProcessingObjectKey("resume-processing/7/" + session.getId() + "/source.pdf");
        session.setExpectedSize(4L);
        session.setExpectedContentType(PDF_CONTENT_TYPE);
        session.setUpdateTime(Instant.now().minusSeconds(60));
        return session;
    }

    /** 构造已经超过处理时限并带有旧 token 的任务。 */
    private ResumeUploadSession timedOutProcessingSession(int attemptCount) {
        ResumeUploadSession session = new ResumeUploadSession();
        session.setId(UUID.randomUUID());
        session.setStatus(ResumeUploadStatus.PROCESSING);
        session.setAttemptCount(attemptCount);
        session.setProcessingStartedAt(Instant.now().minusSeconds(60));
        session.setClaimToken(UUID.randomUUID());
        return session;
    }
}
