package com.keny.jobassistant.model.dto;

import com.keny.jobassistant.model.enums.ApplicationStatus;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Application 返回对象。
 */
@Data
public class ApplicationDTO implements Serializable {

    private Long id;
    private Long jobId;
    private String companyName;
    private String jobTitle;
    private Long resumeId;
    private Long resumeVersionId;
    private Integer resumeVersionNumber;

    /**
     * 即使 Resume 已经被软删除，
     * Application 仍然可以展示投递时的简历名称。
     */
    private String resumeName;
    private ApplicationStatus status;

    /**
     * 当前状态可以转移到的状态，前端看板据此只允许合法拖动。
     * 这只是体验优化，真正的校验在服务端。
     */
    private Set<ApplicationStatus> allowedNextStatuses;
    private LocalDateTime applyTime;
    private LocalDateTime updateTime;
}
