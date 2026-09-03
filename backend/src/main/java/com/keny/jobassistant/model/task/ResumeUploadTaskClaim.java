package com.keny.jobassistant.model.task;

import com.keny.jobassistant.model.enums.ResumeUploadType;

import java.util.UUID;

/** worker 成功领取任务后需要使用的不可变快照，避免处理文件时一直持有数据库事务。 */
public record ResumeUploadTaskClaim(UUID uploadId, UUID claimToken, int attemptCount, Long userId,
                                    ResumeUploadType uploadType, Long targetResumeId, String resumeName,
                                    String originalFilename, String uploadObjectKey, String processingObjectKey, String expectedExtension,
                                    String expectedContentType, long expectedSize) {
}
