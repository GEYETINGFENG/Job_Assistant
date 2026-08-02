package com.keny.jobassistant.service.impl;
import com.fasterxml.jackson.databind.JsonNode;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.dto.ResumeDTO;
import com.keny.jobassistant.model.entity.Resume;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.model.entity.request.ResumeCreateRequest;
import com.keny.jobassistant.repository.ResumeRepository;
import com.keny.jobassistant.repository.UserRepository;
import com.keny.jobassistant.security.CurrentUserProvider;
import com.keny.jobassistant.service.ResumeFileStorageService;
import com.keny.jobassistant.service.ResumeParserService;
import com.keny.jobassistant.service.ResumeService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDateTime;

/**
 * 简历服务实现类。
 * 资源归属规则：
 * 1. 创建简历时，所有者从 JWT 获取
 * 2. 查询简历时，同时使用简历 ID 和当前用户 ID
 * 3. 客户端不能自行指定 userId
 */
@Service
public class ResumeServiceImpl implements ResumeService {
    // 简历默认状态
    private static final int DEFAULT_STATUS = 0;
    // 简历文件最大为 10 MB
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private final ResumeRepository resumeRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ResumeFileStorageService resumeFileStorageService;
    private final ResumeParserService resumeParserService;
    private final TransactionTemplate transactionTemplate;

    public ResumeServiceImpl(ResumeRepository resumeRepository,
                             UserRepository userRepository,
                             CurrentUserProvider currentUserProvider,
                             ResumeFileStorageService resumeFileStorageService,
                             ResumeParserService resumeParserService,
                             PlatformTransactionManager transactionManager) {
        this.resumeRepository = resumeRepository;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
        this.resumeFileStorageService = resumeFileStorageService;
        this.resumeParserService = resumeParserService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }
    /**
     * 为当前登录用户创建简历，简历所有者从 JWT 获取，不接收客户端传入的 userId
     * fileUrl 由后端保存文件后生成，parsedJson 由后端解析文件后生成，
     */
    @Override
    @Transactional
    public Long createResume(ResumeCreateRequest request, MultipartFile file) {
        validateCreateRequest(request,file);
        // 从 JWT 的 sub 中获取当前登录用户 ID。
        Long currentUserId = currentUserProvider.getCurrentUserId();
        // PDF 文本提取和 AI 调用在数据库事务之外执行，避免长时间占用数据库事务和连接。
        JsonNode parsedJson = resumeParserService.parseResume(file);

        Long resumeId = transactionTemplate.execute(status -> {
            // 根据 JWT 用户 ID 查询当前用户。
            User currentUser = userRepository.findById(currentUserId)
                 .orElseThrow(() -> new BusinessException(ErrorCode.NOT_LOGIN));
            LocalDateTime now = LocalDateTime.now();
            Resume resume = new Resume();
            // 简历所有者只能由后端根据 JWT 设置。
            resume.setUser(currentUser);
            resume.setResumeName(request.getResumeName());
            // 保存前还没有简历 ID，因此暂时不设置 fileUrl。
            resume.setFileUrl(null);
            // 简历 JSON 数据由后端解析文件后生成。
            resume.setParsedJson(parsedJson);
            resume.setStatus(DEFAULT_STATUS);
            resume.setCreateTime(now);
            resume.setUpdateTime(now);
            // 先写入数据库获得简历 ID，文件名随后使用该 ID 生成
            Resume savedResume = resumeRepository.saveAndFlush(resume);
            String fileUrl = resumeFileStorageService.storeResumeFile(savedResume.getId(), file);
            // 如果数据库事务最终回滚，删除已经写入本地磁盘的 PDF，避免产生孤立文件
            // 给当前数据库事务注册一个监听器，当事务结束以后执行 afterCompletion()。
            // 如果事务不是成功提交，就删除刚刚保存的 PDF 文件。
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() { // 事务同步器
                    @Override
                    public void afterCompletion(int transactionStatus) { // 传入事务最终状态
                        if (transactionStatus != TransactionSynchronization.STATUS_COMMITTED) {
                            resumeFileStorageService.deleteResumeFile(savedResume.getId());// 这里是删除本地的PDF文件
                            }
                    }
                }
            );
            // 文件地址只能由后端文件存储服务生成。
            savedResume.setFileUrl(fileUrl);
            savedResume.setUpdateTime(LocalDateTime.now());
            resumeRepository.save(savedResume);// 更新刚刚的那条记录
            return savedResume.getId();
        });
        if (resumeId == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "Failed to create resume");
        }
        return resumeId;
    }

    /**
     * 查询当前用户拥有的指定简历。
     * 查询条件同时包含简历 ID 和当前用户 ID，
     * 因此用户无法查询其他用户的简历。
     */
    @Override
    @Transactional(readOnly = true)
    public ResumeDTO getResume(Long resumeId) {
        validateResumeId(resumeId);
        // 从 JWT 中获取当前用户 ID。
        Long currentUserId = currentUserProvider.getCurrentUserId();
        //查询条件为：resume.id = resumeId，如果简历属于其他用户，查询结果同样为空
        Resume resume = resumeRepository.findByIdAndUser_Id(resumeId, currentUserId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.RESOURCE_NOT_FOUND)
                );
        return toResumeDTO(resume);
    }

    /**
     * 校验创建简历请求。
     */
    private void validateCreateRequest(ResumeCreateRequest request, MultipartFile file) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        if (StringUtils.isBlank(request.getResumeName())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume name cannot be blank");
        }
        if (request.getResumeName().length() > 256) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume name is too long");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume file cannot be empty");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume file is too large");
        }
    }

    /**
     * 校验简历 ID。
     */
    private void validateResumeId(Long resumeId) {
        if (resumeId == null || resumeId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Invalid resume ID");
        }
    }

    /**
     * 将 Resume 实体转换为 ResumeDTO。
     */
    private ResumeDTO toResumeDTO(Resume resume) {
        ResumeDTO dto = new ResumeDTO();
        dto.setId(resume.getId());
        dto.setResumeName(resume.getResumeName());
        dto.setFileUrl(resume.getFileUrl());
        dto.setParsedJson(resume.getParsedJson());
        dto.setStatus(resume.getStatus());
        dto.setCreateTime(resume.getCreateTime());
        dto.setUpdateTime(resume.getUpdateTime());
        return dto;
    }
}