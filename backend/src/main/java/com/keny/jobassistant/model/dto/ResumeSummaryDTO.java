package com.keny.jobassistant.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 简历列表 DTO。
 * 列表接口不返回 parsed_json，避免每页读取和传输大量 JSON 内容。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeSummaryDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 简历 ID。
     */
    private Long id;

    /**
     * 简历名称。
     */
    private String resumeName;

    /**
     * 当前最新版本号。
     */
    private Integer latestVersionNumber;

    /**
     * 简历状态。
     */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
