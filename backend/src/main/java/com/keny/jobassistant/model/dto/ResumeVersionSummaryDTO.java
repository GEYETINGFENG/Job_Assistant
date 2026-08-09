package com.keny.jobassistant.model.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 简历版本列表 DTO。
 * 列表接口不返回完整 JSON，
 * 避免一次返回大量历史版本内容。
 */
@Data
public class ResumeVersionSummaryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 历史版本记录 ID。
     */
    private Long id;

    /**
     * 简历 ID。
     */
    private Long resumeId;

    /**
     * 版本号。
     */
    private Integer versionNumber;

    /**
     * 版本创建时间。
     */
    private LocalDateTime createTime;
}