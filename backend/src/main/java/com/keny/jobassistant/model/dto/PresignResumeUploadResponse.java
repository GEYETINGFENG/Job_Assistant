package com.keny.jobassistant.model.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * S3 预签名上传结果。
 */
public record PresignResumeUploadResponse(
        UUID uploadId,
        String uploadUrl,
        Instant expiresAt,
        Map<String, String> requiredHeaders
) {
}