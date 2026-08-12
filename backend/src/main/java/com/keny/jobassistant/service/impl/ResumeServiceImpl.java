package com.keny.jobassistant.service.impl;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.dto.ResumeDTO;
import com.keny.jobassistant.model.dto.ResumeVersionDTO;
import com.keny.jobassistant.model.dto.ResumeVersionSummaryDTO;
import com.keny.jobassistant.model.entity.Resume;
import com.keny.jobassistant.model.entity.ResumeVersion;
import com.keny.jobassistant.model.entity.request.ResumeUpdateRequest;
import com.keny.jobassistant.repository.ResumeRepository;
import com.keny.jobassistant.repository.ResumeVersionRepository;
import com.keny.jobassistant.security.CurrentUserProvider;
import com.keny.jobassistant.service.ResumeService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 简历服务实现类。
 * 资源归属规则：
 * 1. 创建简历时，所有者从 JWT 获取
 * 2. 查询简历时，同时使用简历 ID 和当前用户 ID
 * 3. 客户端不能自行指定 userId
 */
@Service
@Slf4j
public class ResumeServiceImpl implements ResumeService {
    private final ResumeRepository resumeRepository;
    private final ResumeVersionRepository resumeVersionRepository;
    private final CurrentUserProvider currentUserProvider;

    public ResumeServiceImpl(ResumeRepository resumeRepository,
                             ResumeVersionRepository resumeVersionRepository,
                             CurrentUserProvider currentUserProvider) {
        this.resumeRepository = resumeRepository;
        this.resumeVersionRepository = resumeVersionRepository;
        this.currentUserProvider = currentUserProvider;
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
        Resume resume = resumeRepository
                .findByIdAndUser_IdAndIsDelete(resumeId, currentUserId, Resume.NOT_DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return toResumeDTO(resume);
    }

    /**
     * 查询某份简历的全部历史版本。
     */
    @Override
    @Transactional(readOnly = true)
    public List<ResumeVersionSummaryDTO> listResumeVersions(Long resumeId) {
        validateResumeId(resumeId);
        Long currentUserId = currentUserProvider.getCurrentUserId();
        //先校验简历归属，避免无法区分简历不存在、无版本记录或属于其他用户的情况。
        if (!resumeRepository.existsByIdAndUser_IdAndIsDelete(resumeId, currentUserId, Resume.NOT_DELETED)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        // 查询该用户指定简历的所有历史版本，并转换为返回给前端的 DTO。
        return resumeVersionRepository
                .findAllByResume_IdAndResume_User_IdAndResume_IsDeleteOrderByVersionNumberDesc(
                        resumeId, currentUserId, Resume.NOT_DELETED)
                .stream()
                .map(this::toResumeVersionSummaryDTO)
                .toList();
    }

    /**
     * 查询某份简历的指定历史版本。
     */
    @Override
    @Transactional(readOnly = true)
    public ResumeVersionDTO getResumeVersion(Long resumeId, Integer versionNumber) {
        validateResumeId(resumeId);
        validateVersionNumber(versionNumber);
        Long currentUserId = currentUserProvider.getCurrentUserId();
        // 根据简历 ID、用户 ID 和版本号查询历史版本，避免访问其他用户资源。
        ResumeVersion resumeVersion = resumeVersionRepository
                .findByResume_IdAndResume_User_IdAndResume_IsDeleteAndVersionNumber(
                        resumeId, currentUserId, Resume.NOT_DELETED, versionNumber)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return toResumeVersionDTO(resumeVersion);
    }
    /**
     * 编辑已有简历。
     * 使用乐观锁防止两个并发请求互相覆盖修改结果。
     */
    @Override
    @Transactional
    public ResumeDTO updateResume(Long resumeId, ResumeUpdateRequest request) {
        validateResumeId(resumeId);

        if (request == null || StringUtils.isBlank(request.getResumeName())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume name cannot be blank");
        }

        if (request.getResumeName().strip().length() > 256) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume name is too long");
        }

        if (request.getLockVersion() == null || request.getLockVersion() < 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Lock version is required");
        }

        Long currentUserId = currentUserProvider.getCurrentUserId();
        Resume resume = resumeRepository
                .findByIdAndUser_IdAndIsDelete(resumeId, currentUserId, Resume.NOT_DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        /*
         * 客户端拿到的是旧版本，
         * 说明这份 Resume 在客户端读取以后已经被修改过。
         */
        if (!request.getLockVersion().equals(resume.getLockVersion())) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "Resume has been modified, please refresh and retry");
        }
        resume.setResumeName(request.getResumeName().strip());
        resume.setUpdateTime(LocalDateTime.now());
        try {
            /*
             * saveAndFlush 必须立即执行 UPDATE，这样乐观锁冲突会在当前方法中直接抛出，
             * 而不是拖到事务提交阶段才发现。
             */
            Resume updatedResume = resumeRepository.saveAndFlush(resume);
            return toResumeDTO(updatedResume);
        } catch (ObjectOptimisticLockingFailureException exception) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK_CONFLICT, "Resume has been modified concurrently");
        }
    }
    /**
     * 软删除 Resume。
     * 不执行 repository.delete()，只修改 is_delete。
     */
    @Override
    @Transactional
    public Boolean deleteResume(Long resumeId) {
        validateResumeId(resumeId);
        Long currentUserId = currentUserProvider.getCurrentUserId();
        Resume resume = resumeRepository
                .findByIdAndUser_IdAndIsDelete(resumeId, currentUserId, Resume.NOT_DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        LocalDateTime now = LocalDateTime.now();
        resume.setIsDelete(Resume.DELETED);
        resume.setDeleteTime(now);
        resume.setUpdateTime(now);
        resumeRepository.saveAndFlush(resume);
        return true;
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
     * 校验版本号。
     */
    private void validateVersionNumber(Integer versionNumber) {
        if (versionNumber == null || versionNumber <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Invalid resume version number");
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
        dto.setLatestVersionNumber(resume.getLatestVersionNumber());
        dto.setLockVersion(resume.getLockVersion());
        dto.setCreateTime(resume.getCreateTime());
        dto.setUpdateTime(resume.getUpdateTime());
        return dto;
    }
    /**
     * ResumeVersion 转换为版本列表 DTO
     */
    private ResumeVersionSummaryDTO toResumeVersionSummaryDTO(ResumeVersion version) {
        ResumeVersionSummaryDTO dto = new ResumeVersionSummaryDTO();
        dto.setId(version.getId());
        dto.setResumeId(version.getResume().getId());
        dto.setVersionNumber(version.getVersionNumber());
        dto.setCreateTime(version.getCreateTime());
        return dto;
    }

    /**
     * ResumeVersion 转换为版本详情 DTO
     */
    private ResumeVersionDTO toResumeVersionDTO(ResumeVersion version) {
        ResumeVersionDTO dto = new ResumeVersionDTO();
        dto.setId(version.getId());
        dto.setResumeId(version.getResume().getId());
        dto.setVersionNumber(version.getVersionNumber());
        dto.setContentJson(version.getContentJson());
        dto.setCreateTime(version.getCreateTime());
        return dto;
    }
}