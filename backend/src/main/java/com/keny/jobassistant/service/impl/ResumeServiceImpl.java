package com.keny.jobassistant.service.impl;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.dto.ResumeDTO;
import com.keny.jobassistant.model.entity.Resume;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.model.entity.request.ResumeCreateRequest;
import com.keny.jobassistant.repository.ResumeRepository;
import com.keny.jobassistant.repository.UserRepository;
import com.keny.jobassistant.security.CurrentUserProvider;
import com.keny.jobassistant.service.ResumeService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    /**
     * 简历默认状态。
     */
    private static final int DEFAULT_STATUS = 0;
    private final ResumeRepository resumeRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;

    public ResumeServiceImpl(ResumeRepository resumeRepository, UserRepository userRepository,
                             CurrentUserProvider currentUserProvider) {
        this.resumeRepository = resumeRepository;
        this.userRepository = userRepository;
        this.currentUserProvider = currentUserProvider;
    }
    /**
     * 为当前登录用户创建简历，简历所有者从 JWT 获取，
     * 不接收客户端传入的 userId。
     */
    @Override
    @Transactional
    public Long createResume(ResumeCreateRequest request) {
        validateCreateRequest(request);
        // 从 JWT 的 sub 中获取当前登录用户 ID。
        Long currentUserId = currentUserProvider.getCurrentUserId();
        // 根据 JWT 用户 ID 查询当前用户。
        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_LOGIN));
        LocalDateTime now = LocalDateTime.now();
        Resume resume = new Resume();
        // 简历所有者只能由后端根据 JWT 设置。
        resume.setUser(currentUser);
        resume.setResumeName(request.getResumeName());
        resume.setFileUrl(request.getFileUrl());
        resume.setParsedJson(request.getParsedJson());
        resume.setStatus(DEFAULT_STATUS);
        resume.setCreateTime(now);
        resume.setUpdateTime(now);
        Resume savedResume = resumeRepository.save(resume);
        return savedResume.getId();
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
    private void validateCreateRequest(ResumeCreateRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        if (StringUtils.isBlank(request.getResumeName())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume name cannot be blank");
        }
        if (request.getResumeName().length() > 256) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume name is too long");
        }
        if (request.getFileUrl() != null && request.getFileUrl().length() > 1024) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume file URL is too long");
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