package com.keny.jobassistant.model.entity.request;

/**
 * 申请 S3 预签名上传地址。
 *
 * fileSize 是本地文件的实际字节数，不是 KB 或 MB。
 */
public record PresignResumeUploadRequest(
        String resumeName,
        String filename,
        Long fileSize
) {
}