package com.keny.jobassistant.model.dto;

import com.keny.jobassistant.model.enums.ResumeUploadStatus;

import java.util.UUID;

/** S3 简历上传确认响应；record 只负责携带任务状态和最终结果，不放业务逻辑。 */
public record ResumeUploadCompleteResponse(UUID uploadId, ResumeUploadStatus status, Long resumeId, Integer versionNumber) {
}
