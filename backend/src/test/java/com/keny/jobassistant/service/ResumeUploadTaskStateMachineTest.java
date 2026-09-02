package com.keny.jobassistant.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.document.ResumeParseResult;
import com.keny.jobassistant.model.entity.Resume;
import com.keny.jobassistant.model.entity.ResumeUploadSession;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.model.entity.request.PresignResumeUploadRequest;
import com.keny.jobassistant.model.enums.ResumeUploadStatus;
import com.keny.jobassistant.model.enums.ResumeUploadType;
import com.keny.jobassistant.repository.ResumeRepository;
import com.keny.jobassistant.repository.ResumeUploadSessionRepository;
import com.keny.jobassistant.repository.ResumeVersionAtomicRepository;
import com.keny.jobassistant.repository.UserRepository;
import com.keny.jobassistant.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResumeUploadTaskStateMachineTest {

    private static final Long USER_ID = 7L;
    private static final String PDF_CONTENT_TYPE = "application/pdf";

    @Mock
    private ResumeS3StorageService s3StorageService;
    @Mock
    private ResumeParserService resumeParserService;
    @Mock
    private ResumeUploadSessionRepository uploadSessionRepository;
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

    private ResumeS3UploadService service;
    private User user;

    /** 初始化被测服务及当前用户。 */
    @BeforeEach
    void setUp() {
        service = new ResumeS3UploadService(
                s3StorageService,
                resumeParserService,
                uploadSessionRepository,
                resumeRepository,
                resumeVersionAtomicRepository,
                userRepository,
                currentUserProvider,
                transactionManager,
                5,
                "resume-uploads",
                "resumes"
        );

        user = new User();
        user.setId(USER_ID);
    }

    /** 创建预签名上传后，任务应处于 UPLOADING，并且处理元数据保持初始状态。 */
    @Test
    void presignShouldCreateUploadingSessionWithCleanTaskMetadata() {
        when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(s3StorageService.createPresignedUpload(anyString(), eq(PDF_CONTENT_TYPE), any()))
                .thenReturn(new ResumeS3StorageService.PresignedUploadResult(
                        "https://example.test/upload",
                        Instant.now().plusSeconds(300),
                        Map.of("Content-Type", PDF_CONTENT_TYPE)
                ));

        service.createPresignedUpload(new PresignResumeUploadRequest("Backend Resume", "resume.pdf", 4L));

        ArgumentCaptor<ResumeUploadSession> sessionCaptor = ArgumentCaptor.forClass(ResumeUploadSession.class);
        verify(uploadSessionRepository).saveAndFlush(sessionCaptor.capture());
        ResumeUploadSession session = sessionCaptor.getValue();

        assertThat(session.getStatus()).isEqualTo(ResumeUploadStatus.UPLOADING);
        assertThat(session.getAttemptCount()).isZero();
        assertThat(session.getNextAttemptAt()).isNull();
        assertThat(session.getProcessingStartedAt()).isNull();
        assertThat(session.getClaimToken()).isNull();
        assertThat(session.getLastErrorCode()).isNull();
        assertThat(session.getLastErrorMessage()).isNull();
    }

    /** 上传处理成功后，应记录一次尝试、进入 COMPLETED，并清除本次领取信息。 */
    @Test
    void completeShouldRecordAttemptAndClearClaimMetadataOnSuccess() throws Exception {
        enableTransactions();
        ResumeUploadSession session = newUploadingSession(Instant.now().plusSeconds(300));
        when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
        when(uploadSessionRepository.findForUpdateByIdAndUserId(session.getId(), USER_ID))
                .thenReturn(Optional.of(session));
        when(s3StorageService.getObjectMetadata(session.getUploadObjectKey()))
                .thenReturn(new ResumeS3StorageService.StoredObjectMetadata(4L, PDF_CONTENT_TYPE, "etag"));
        doAnswer(invocation -> {
            Path target = invocation.getArgument(1);
            Files.write(target, new byte[]{1, 2, 3, 4});
            return null;
        }).when(s3StorageService).downloadObject(eq(session.getUploadObjectKey()), any(Path.class));
        when(resumeParserService.parseResume(any()))
                .thenReturn(new ResumeParseResult(
                        JsonNodeFactory.instance.objectNode().put("name", "Test User"),
                        PDF_CONTENT_TYPE,
                        ".pdf"
                ));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(resumeRepository.saveAndFlush(any(Resume.class))).thenAnswer(invocation -> {
            Resume resume = invocation.getArgument(0);
            resume.setId(99L);
            return resume;
        });
        when(resumeVersionAtomicRepository.createNextVersion(eq(99L), eq(USER_ID), anyString(), anyString(), any()))
                .thenReturn(Optional.of(1));

        service.completeUpload(session.getId());

        assertThat(session.getStatus()).isEqualTo(ResumeUploadStatus.COMPLETED);
        assertThat(session.getAttemptCount()).isEqualTo(1);
        assertThat(session.getProcessingStartedAt()).isNull();
        assertThat(session.getClaimToken()).isNull();
        assertThat(session.getLastErrorCode()).isNull();
        assertThat(session.getLastErrorMessage()).isNull();
        verify(transactionManager, atLeast(2)).commit(transactionStatus);
    }

    /** 当前领取的处理任务失败后，应进入 DEAD 并保留便于排查的错误信息。 */
    @Test
    void processingFailureShouldMoveCurrentClaimToDead() {
        enableTransactions();
        ResumeUploadSession session = newUploadingSession(Instant.now().plusSeconds(300));
        when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
        when(uploadSessionRepository.findForUpdateByIdAndUserId(session.getId(), USER_ID))
                .thenReturn(Optional.of(session));
        when(s3StorageService.getObjectMetadata(session.getUploadObjectKey()))
                .thenThrow(new BusinessException(ErrorCode.SYSTEM_ERROR, "S3 is unavailable"));

        assertThatThrownBy(() -> service.completeUpload(session.getId()))
                .isInstanceOf(BusinessException.class);

        assertThat(session.getStatus()).isEqualTo(ResumeUploadStatus.DEAD);
        assertThat(session.getAttemptCount()).isEqualTo(1);
        assertThat(session.getProcessingStartedAt()).isNull();
        assertThat(session.getClaimToken()).isNull();
        assertThat(session.getLastErrorCode()).isEqualTo(Integer.toString(ErrorCode.SYSTEM_ERROR.getCode()));
        assertThat(session.getLastErrorMessage()).isEqualTo("S3 is unavailable");
    }

    /** 已过期的上传会话应先持久化为 EXPIRED，再向调用方返回错误。 */
    @Test
    void expiredUploadingSessionShouldBePersistedAsExpiredBeforeReturningError() {
        enableTransactions();
        ResumeUploadSession session = newUploadingSession(Instant.now().minusSeconds(1));
        when(currentUserProvider.getCurrentUserId()).thenReturn(USER_ID);
        when(uploadSessionRepository.findForUpdateByIdAndUserId(session.getId(), USER_ID))
                .thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.completeUpload(session.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessage(ErrorCode.PARAMS_ERROR.getMessage());

        assertThat(session.getStatus()).isEqualTo(ResumeUploadStatus.EXPIRED);
        assertThat(session.getAttemptCount()).isZero();
        verifyNoInteractions(resumeParserService);
        verify(transactionManager).commit(transactionStatus);
    }

    /** 让 TransactionTemplate 使用 Mockito 模拟的事务。 */
    private void enableTransactions() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(transactionStatus);
    }

    /** 构造处于 UPLOADING 状态的测试会话。 */
    private ResumeUploadSession newUploadingSession(Instant expiresAt) {
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
        session.setStatus(ResumeUploadStatus.UPLOADING);
        session.setUploadType(ResumeUploadType.CREATE);
        session.setAttemptCount(0);
        session.setExpiresAt(expiresAt);
        session.setCreateTime(now);
        session.setUpdateTime(now);
        return session;
    }
}
