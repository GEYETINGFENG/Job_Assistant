package com.keny.jobassistant.service.impl;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.dto.ApplicationDTO;
import com.keny.jobassistant.model.dto.ApplicationEventDTO;
import com.keny.jobassistant.model.dto.ApplicationEventPageDTO;
import com.keny.jobassistant.model.entity.Application;
import com.keny.jobassistant.model.entity.ApplicationEvent;
import com.keny.jobassistant.model.entity.Job;
import com.keny.jobassistant.model.entity.Resume;
import com.keny.jobassistant.model.entity.ResumeVersion;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.model.entity.request.ApplicationCreateRequest;
import com.keny.jobassistant.model.entity.request.ApplicationStatusUpdateRequest;
import com.keny.jobassistant.model.enums.ApplicationStatus;
import com.keny.jobassistant.repository.ApplicationEventRepository;
import com.keny.jobassistant.repository.ApplicationRepository;
import com.keny.jobassistant.repository.JobRepository;
import com.keny.jobassistant.repository.ResumeVersionRepository;
import com.keny.jobassistant.repository.UserRepository;
import com.keny.jobassistant.security.CurrentUserProvider;
import com.keny.jobassistant.service.ApplicationService;
import jakarta.annotation.Resource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ApplicationServiceImpl implements ApplicationService {

    private static final int MAX_EVENT_PAGE_SIZE = 100;
    private static final int MAX_NOTE_LENGTH = 512;

    @Resource
    private ApplicationRepository applicationRepository;

    @Resource
    private ApplicationEventRepository applicationEventRepository;

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
        LocalDateTime now = LocalDateTime.now();
        application.setStatus(ApplicationStatus.APPLIED);
        application.setApplyTime(now);
        application.setUpdateTime(now);
        Application savedApplication = applicationRepository.save(application);
        // 创建本身也是一次状态变化：历史从 null -> APPLIED 开始。
        recordEvent(savedApplication, null, ApplicationStatus.APPLIED, currentUserId, null, now);
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
     * 修改申请状态，状态更新和事件记录在同一个事务中。
     * 1. 应用层先查转移表，非法转移直接拒绝，不访问数据库；
     * 2. 条件更新 WHERE status = fromStatus，由数据库原子地判断前提条件；
     *    两个请求基于同一个旧状态并发修改时，只有一个能匹配到这一行。
     */
    @Override
    @Transactional
    public ApplicationDTO updateStatus(Long applicationId, ApplicationStatusUpdateRequest request) {
        if (applicationId == null || applicationId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        if (request == null || request.getFromStatus() == null || request.getToStatus() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "fromStatus and toStatus are required");
        }
        if (request.getNote() != null && request.getNote().length() > MAX_NOTE_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Note must not exceed " + MAX_NOTE_LENGTH + " characters");
        }
        ApplicationStatus fromStatus = request.getFromStatus();
        ApplicationStatus toStatus = request.getToStatus();
        if (!fromStatus.canTransitionTo(toStatus)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Cannot change application status from " + fromStatus + " to " + toStatus);
        }

        Long currentUserId = currentUserProvider.getCurrentUserId();
        LocalDateTime now = LocalDateTime.now();
        int updatedRows = applicationRepository.transitionStatus(applicationId, currentUserId, fromStatus, toStatus, now);
        if (updatedRows == 0) {
            // 区分“不存在 / 不属于当前用户”和“状态已经被别人改掉”。
            if (!applicationRepository.existsByIdAndUser_Id(applicationId, currentUserId)) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            throw new BusinessException(ErrorCode.APPLICATION_STATUS_CONFLICT,
                    "Application status is no longer " + fromStatus + ", reload and try again");
        }
        recordEvent(applicationRepository.getReferenceById(applicationId),
                fromStatus, toStatus, currentUserId, request.getNote(), now);
        return getApplication(applicationId);
    }

    /**
     * 游标分页：WHERE id < cursor ORDER BY id DESC LIMIT size + 1。
     * 多取一条用于判断是否还有下一页，不需要 count(*)。
     */
    @Override
    @Transactional(readOnly = true)
    public ApplicationEventPageDTO listEvents(Long applicationId, Long cursor, int size) {
        if (applicationId == null || applicationId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        if (cursor != null && cursor <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Cursor must be positive");
        }
        if (size < 1 || size > MAX_EVENT_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Page size must be between 1 and " + MAX_EVENT_PAGE_SIZE);
        }
        Long currentUserId = currentUserProvider.getCurrentUserId();
        if (!applicationRepository.existsByIdAndUser_Id(applicationId, currentUserId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        PageRequest limit = PageRequest.of(0, size + 1);
        List<ApplicationEvent> events = cursor == null
                ? applicationEventRepository.findByApplication_IdOrderByIdDesc(applicationId, limit)
                : applicationEventRepository.findByApplication_IdAndIdLessThanOrderByIdDesc(applicationId, cursor, limit);

        boolean hasNext = events.size() > size;
        List<ApplicationEventDTO> items = events.stream()
                .limit(size)
                .map(this::toApplicationEventDTO)
                .toList();
        Long nextCursor = hasNext ? items.get(items.size() - 1).getId() : null;
        return new ApplicationEventPageDTO(items, nextCursor, hasNext);
    }

    private void recordEvent(Application application, ApplicationStatus fromStatus, ApplicationStatus toStatus,
                             Long operatorUserId, String note, LocalDateTime createTime) {
        ApplicationEvent event = new ApplicationEvent();
        event.setApplication(application);
        event.setFromStatus(fromStatus);
        event.setToStatus(toStatus);
        event.setOperatorUserId(operatorUserId);
        event.setNote(note);
        event.setCreateTime(createTime);
        applicationEventRepository.save(event);
    }

    private ApplicationEventDTO toApplicationEventDTO(ApplicationEvent event) {
        ApplicationEventDTO dto = new ApplicationEventDTO();
        dto.setId(event.getId());
        dto.setFromStatus(event.getFromStatus());
        dto.setToStatus(event.getToStatus());
        dto.setOperatorUserId(event.getOperatorUserId());
        dto.setNote(event.getNote());
        dto.setCreateTime(event.getCreateTime());
        return dto;
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
        dto.setAllowedNextStatuses(application.getStatus().allowedNext());
        dto.setApplyTime(application.getApplyTime());
        dto.setUpdateTime(application.getUpdateTime());
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