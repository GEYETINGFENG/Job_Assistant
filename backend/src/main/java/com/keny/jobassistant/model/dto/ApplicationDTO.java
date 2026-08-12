package com.keny.jobassistant.model.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

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
    private Integer status;
    private LocalDateTime applyTime;
}