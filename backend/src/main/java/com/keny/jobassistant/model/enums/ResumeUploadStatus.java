package com.keny.jobassistant.model.enums;

/**
 * S3 简历上传会话状态。
 */
public enum ResumeUploadStatus {

    // 已生成预签名 URL，等待客户端上传和确认。
    PENDING,

    // 已收到确认请求，正在校验和解析。
    PROCESSING,

    // 文件校验、解析及 Resume 创建全部成功。
    COMPLETED,

    // 文件校验或解析失败。
    FAILED,

    // 上传会话已过期。
    EXPIRED
}