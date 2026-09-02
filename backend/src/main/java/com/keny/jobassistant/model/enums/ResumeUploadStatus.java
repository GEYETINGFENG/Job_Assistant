package com.keny.jobassistant.model.enums;

/**
 * S3 简历上传会话状态。
 */
public enum ResumeUploadStatus {

    // 已生成预签名 URL，等待客户端上传和确认。
    UPLOADING,

    // 文件已经确认到达，等待后台 worker 处理。
    PENDING,

    // worker 已领取任务，正在校验和解析。
    PROCESSING,

    // 文件校验、解析及 Resume 创建全部成功。
    COMPLETED,

    // 文件无法处理或重试次数已经耗尽，需要人工检查。
    DEAD,

    // 上传会话已过期。
    EXPIRED
}
