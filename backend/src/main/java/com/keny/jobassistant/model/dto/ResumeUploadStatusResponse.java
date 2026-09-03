package com.keny.jobassistant.model.dto;

import com.keny.jobassistant.model.enums.ResumeUploadStatus;

import java.time.Instant;
import java.util.UUID;

/** 客户端轮询结果，包含当前状态、重试信息以及完成后的简历 ID 和版本号。 */
public record ResumeUploadStatusResponse(UUID uploadId, ResumeUploadStatus status, int attemptCount,
                                         Instant nextAttemptAt, Long resumeId, Integer versionNumber,
                                         String lastErrorCode, String lastErrorMessage,
                                         Instant createTime, Instant updateTime) {
}
