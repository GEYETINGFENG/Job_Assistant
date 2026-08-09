package com.keny.jobassistant.model.enums;

/**
 * 简历上传类型。
 */
public enum ResumeUploadType {

    /**
     * 创建一份全新的简历，并生成 V1。
     */
    CREATE,

    /**
     * 为已有简历增加 V2、V3 等新版本。
     */
    NEW_VERSION
}