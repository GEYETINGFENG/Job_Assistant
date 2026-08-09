//package com.keny.jobassistant.service;
//
//import com.keny.jobassistant.common.ErrorCode;
//import com.keny.jobassistant.exception.BusinessException;
//import com.keny.jobassistant.repository.ResumeRepository;
//import com.keny.jobassistant.repository.UserRepository;
//import com.keny.jobassistant.security.CurrentUserProvider;
//import com.keny.jobassistant.service.impl.ResumeServiceImpl;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import org.springframework.mock.web.MockMultipartFile;
//import org.springframework.transaction.PlatformTransactionManager;
//import org.springframework.web.multipart.MultipartFile;
//
//import java.nio.charset.StandardCharsets;
//
//import static org.assertj.core.api.Assertions.assertThat;
//import static org.junit.jupiter.api.Assertions.assertThrows;
//import static org.mockito.Mockito.*;
//
///**
// * 简历上传安全测试。
// *
// * 验证伪造文件和超大文件能够被正确拒绝。
// */
//@ExtendWith(MockitoExtension.class)
//class ResumeUploadSecurityTest {
//
//    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
//    @Mock
//    private ResumeRepository resumeRepository;
//    @Mock
//    private UserRepository userRepository;
//    @Mock
//    private CurrentUserProvider currentUserProvider;
//    @Mock
//    private ResumeFileStorageService resumeFileStorageService;
//    @Mock
//    private ResumeParserService resumeParserService;
//    @Mock
//    private PlatformTransactionManager transactionManager;
//    private TikaResumeDocumentExtractor documentExtractor;
//    private ResumeServiceImpl resumeService;
//
//    @BeforeEach
//    void setUp() {
//        documentExtractor = new TikaResumeDocumentExtractor(
//                100_000,
//                2_000,
//                20L * 1024 * 1024,
//                50L * 1024 * 1024,
//                100,
//                1024 * 1024
//        );
//
//        resumeService = new ResumeServiceImpl(
//                resumeRepository,
//                userRepository,
//                currentUserProvider,
//                resumeFileStorageService,
//                resumeParserService,
//                transactionManager
//        );
//    }
//
//    /**
//     * 普通文本即使扩展名和 Content-Type 都伪装为 PDF，
//     * 仍然应被 Tika 根据真实内容识别并拒绝。
//     */
//    @Test
//    void shouldRejectPlainTextDisguisedAsPdf() {
//        MockMultipartFile fakePdf = new MockMultipartFile(
//                "file",
//                "fake-resume.pdf",
//                "application/pdf",
//                "This is plain text, not a real PDF."
//                        .getBytes(StandardCharsets.UTF_8)
//        );
//
//        BusinessException exception = assertThrows(
//                BusinessException.class,
//                () -> documentExtractor.extract(fakePdf)
//        );
//
//        assertThat(exception.getCode()).isEqualTo(ErrorCode.PARAMS_ERROR.getCode());
//        assertThat(exception.getDescription()).contains("Only PDF and DOCX");
//    }
//
//
//    /**
//     * 超过 10 MB 的文件应在 Tika、AI、数据库和文件存储执行前被拒绝。
//     */
//    @Test
//    void shouldRejectOversizedFileBeforeParsingAndStorage() {
//        ResumeCreateRequest request = new ResumeCreateRequest();
//        request.setResumeName("Oversized resume");
//
//        MultipartFile oversizedFile = mock(MultipartFile.class);
//        when(oversizedFile.isEmpty()).thenReturn(false);//模拟文件不是空的
//        when(oversizedFile.getSize()).thenReturn(MAX_FILE_SIZE + 1);//模拟文件大小
//
//        BusinessException exception = assertThrows(
//                BusinessException.class,
//                () -> resumeService.createResume(request, oversizedFile)
//        );
//
//        assertThat(exception.getCode()).isEqualTo(ErrorCode.PARAMS_ERROR.getCode());
//        assertThat(exception.getDescription()).isEqualTo("Resume file is too large");
//
//        /*
//         * 文件在基础大小校验阶段已经被拒绝，
//         * 后面的用户查询，Tika，AI，数据库和文件存储都不应执行。
//         * 从测试开始到现在，这个 Mock 对象没有发生任何方法调用。
//         */
//        verifyNoInteractions(currentUserProvider);
//        verifyNoInteractions(resumeParserService);
//        verifyNoInteractions(resumeFileStorageService);
//        verifyNoInteractions(resumeRepository);
//        verifyNoInteractions(userRepository);
//    }
//}