package com.keny.jobassistant.model.dto;

/**
 * S3 简历上传完成响应。
 *
 * @param resumeId 简历 ID
 * @param versionNumber 本次上传创建的版本号
 */
public record ResumeUploadCompleteResponse(
        Long resumeId,
        Integer versionNumber
) {
}