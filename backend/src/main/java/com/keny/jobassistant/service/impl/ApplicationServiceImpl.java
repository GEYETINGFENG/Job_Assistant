package com.keny.jobassistant.service.impl;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.dto.ApplicationDTO;
import com.keny.jobassistant.model.entity.Application;
import com.keny.jobassistant.model.entity.Job;
import com.keny.jobassistant.model.entity.Resume;
import com.keny.jobassistant.model.entity.ResumeVersion;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.model.entity.request.ApplicationCreateRequest;
import com.keny.jobassistant.repository.ApplicationRepository;
import com.keny.jobassistant.repository.JobRepository;
import com.keny.jobassistant.repository.ResumeVersionRepository;
import com.keny.jobassistant.repository.UserRepository;
import com.keny.jobassistant.security.CurrentUserProvider;
import com.keny.jobassistant.service.ApplicationService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ApplicationServiceImpl implements ApplicationService {

    @Resource
    private ApplicationRepository applicationRepository;

    @Resource
    private ResumeVersionRepository resumeVersionRepository;

    @Resource
    private JobRepository jobRepository;

    @Resource
    private UserRepository userRepository;

    @Resource
    private CurrentUserProvider currentUserProvider;

    /**
     * 创建岗位申请。
     * Application 保存投递时实际使用的 ResumeVersion，
     * 而不是只保存 Resume。
     */
    @Override
    @Transactional
    public ApplicationDTO createApplication(ApplicationCreateRequest request) {
        if (request == null || request.getJobId() == null || request.getJobId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Job id is invalid");
        }
        if (request.getResumeVersionId() == null || request.getResumeVersionId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Resume version id is invalid");
        }
        Long currentUserId = currentUserProvider.getCurrentUserId();
        // 防止同一个用户重复申请同一个 Job
        if (applicationRepository.existsByUser_IdAndJob_Id(currentUserId, request.getJobId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "You have already applied for this job");
        }
        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Job job = jobRepository.findByIdAndUser_Id(request.getJobId(), currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));

        /*
         * 创建新 Application 时：
         * 1. ResumeVersion 必须属于当前用户；
         * 2. Resume 必须仍然是有效状态；
         * 3. 已软删除的 Resume 不能再用于新的申请。
         */
        ResumeVersion resumeVersion = resumeVersionRepository
                .findByIdAndResume_User_IdAndResume_IsDelete(request.getResumeVersionId(), currentUserId, Resume.NOT_DELETED)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Resume version does not exist"));

        Application application = new Application();
        application.setUser(user);
        application.setJob(job);
        application.setResumeVersion(resumeVersion);
        application.setStatus(0);
        application.setApplyTime(LocalDateTime.now());
        Application savedApplication = applicationRepository.save(application);
        return toApplicationDTO(savedApplication);
    }

    /**
     * 查询一条历史 Application。
     * 这里不能要求 Resume.isDelete = 0。
     * 因为 Resume 即使后来被用户软删除，
     * Application 仍然需要保留投递历史。
     */
    @Override
    @Transactional(readOnly = true)
    public ApplicationDTO getApplication(Long applicationId) {
        if (applicationId == null || applicationId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Long currentUserId = currentUserProvider.getCurrentUserId();
        Application application = applicationRepository.findByIdAndUser_Id(applicationId, currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return toApplicationDTO(application);
    }

    /**
     * 查询当前用户全部 Application。
     */
    @Override
    @Transactional(readOnly = true)
    public List<ApplicationDTO> listApplications() {
        Long currentUserId = currentUserProvider.getCurrentUserId();
        return applicationRepository.findAllByUser_IdOrderByApplyTimeDesc(currentUserId)
                .stream()
                .map(this::toApplicationDTO)
                .toList();
    }

    /**
     * Entity 转 DTO。
     * 旧 Application 的 resumeVersion 可能为 NULL
     * 因为数据库迁移前没有保存 ResumeVersion。
     */
    private ApplicationDTO toApplicationDTO(Application application) {
        ApplicationDTO dto = new ApplicationDTO();
        dto.setId(application.getId());
        dto.setJobId(application.getJob().getId());
        dto.setStatus(application.getStatus());
        dto.setApplyTime(application.getApplyTime());
        Job job = application.getJob();
        if (job != null) {
            dto.setCompanyName(job.getCompanyName());
            dto.setJobTitle(job.getJobTitle());
        }
        ResumeVersion resumeVersion = application.getResumeVersion();
        if (resumeVersion != null) {
            dto.setResumeVersionId(resumeVersion.getId());
            dto.setResumeVersionNumber(resumeVersion.getVersionNumber());
            dto.setResumeId(resumeVersion.getResume().getId());
            dto.setResumeName(resumeVersion.getResume().getResumeName());
        }
        return dto;
    }
}