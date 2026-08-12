package com.keny.jobassistant.model.entity.request;

import lombok.Data;

/**
 * 创建岗位申请请求。
 */
@Data
public class ApplicationCreateRequest {

    /**
     * 要申请的 Job。
     */
    private Long jobId;

    /**
     * 本次申请实际使用的 ResumeVersion。
     * 记录具体版本，确保以后 Resume 更新或删除后，
     * Application 仍然可以知道当时使用了哪份简历。
     */
    private Long resumeVersionId;
}