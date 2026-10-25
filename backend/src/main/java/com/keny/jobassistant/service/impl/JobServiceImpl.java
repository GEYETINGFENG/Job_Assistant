package com.keny.jobassistant.service.impl;

import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.dto.JobDTO;
import com.keny.jobassistant.model.dto.JobPageDTO;
import com.keny.jobassistant.model.entity.Job;
import com.keny.jobassistant.model.entity.User;
import com.keny.jobassistant.model.entity.request.JobCreateRequest;
import com.keny.jobassistant.repository.JobRepository;
import com.keny.jobassistant.repository.UserRepository;
import com.keny.jobassistant.security.CurrentUserProvider;
import com.keny.jobassistant.service.JobService;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class JobServiceImpl implements JobService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_NAME_LENGTH = 256;
    private static final int MAX_SALARY_LENGTH = 128;
    // 足够容纳一份完整 JD，同时限制发送给 LLM 的输入大小。
    private static final int MAX_DESCRIPTION_LENGTH = 20000;

    @Resource
    private JobRepository jobRepository;

    @Resource
    private UserRepository userRepository;

    @Resource
    private CurrentUserProvider currentUserProvider;

    @Override
    @Transactional
    public JobDTO createJob(JobCreateRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.NULL_ERROR);
        }
        String companyName = requireText(request.getCompanyName(), "Company name", MAX_NAME_LENGTH);
        String jobTitle = requireText(request.getJobTitle(), "Job title", MAX_NAME_LENGTH);
        String description = requireText(request.getDescription(), "Job description", MAX_DESCRIPTION_LENGTH);
        String location = optionalText(request.getLocation(), "Location", MAX_NAME_LENGTH);
        String salary = optionalText(request.getSalary(), "Salary", MAX_SALARY_LENGTH);

        Long currentUserId = currentUserProvider.getCurrentUserId();
        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_LOGIN));

        LocalDateTime now = LocalDateTime.now();
        Job job = new Job();
        job.setUser(user);
        job.setCompanyName(companyName);
        job.setJobTitle(jobTitle);
        job.setDescription(description);
        job.setLocation(location);
        job.setSalary(salary);
        job.setCreateTime(now);
        job.setUpdateTime(now);
        return toJobDTO(jobRepository.save(job));
    }

    @Override
    @Transactional(readOnly = true)
    public JobDTO getJob(Long jobId) {
        if (jobId == null || jobId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        Long currentUserId = currentUserProvider.getCurrentUserId();
        return jobRepository.findByIdAndUser_Id(jobId, currentUserId)
                .map(this::toJobDTO)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 游标分页：WHERE id < cursor ORDER BY id DESC LIMIT size + 1，多取一条判断是否还有下一页。
     */
    @Override
    @Transactional(readOnly = true)
    public JobPageDTO listJobs(Long cursor, int size) {
        if (cursor != null && cursor <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Cursor must be positive");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        Long currentUserId = currentUserProvider.getCurrentUserId();
        PageRequest limit = PageRequest.of(0, size + 1);
        List<Job> jobs = cursor == null
                ? jobRepository.findByUser_IdOrderByIdDesc(currentUserId, limit)
                : jobRepository.findByUser_IdAndIdLessThanOrderByIdDesc(currentUserId, cursor, limit);

        boolean hasNext = jobs.size() > size;
        List<JobDTO> items = jobs.stream().limit(size).map(this::toJobDTO).toList();
        Long nextCursor = hasNext ? items.get(items.size() - 1).getId() : null;
        return new JobPageDTO(items, nextCursor, hasNext);
    }

    private String requireText(String value, String field, int maxLength) {
        if (StringUtils.isBlank(value)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, field + " is required");
        }
        return checkLength(value.strip(), field, maxLength);
    }

    private String optionalText(String value, String field, int maxLength) {
        return StringUtils.isBlank(value) ? null : checkLength(value.strip(), field, maxLength);
    }

    private String checkLength(String value, String field, int maxLength) {
        if (value.length() > maxLength) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, field + " must not exceed " + maxLength + " characters");
        }
        return value;
    }

    private JobDTO toJobDTO(Job job) {
        JobDTO dto = new JobDTO();
        dto.setId(job.getId());
        dto.setCompanyName(job.getCompanyName());
        dto.setJobTitle(job.getJobTitle());
        dto.setDescription(job.getDescription());
        dto.setLocation(job.getLocation());
        dto.setSalary(job.getSalary());
        dto.setCreateTime(job.getCreateTime());
        dto.setUpdateTime(job.getUpdateTime());
        return dto;
    }
}
