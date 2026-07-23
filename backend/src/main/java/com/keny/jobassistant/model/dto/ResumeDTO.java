package com.keny.jobassistant.model.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 简历响应 DTO。
 */
@Data
public class ResumeDTO implements Serializable {

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
     * 简历文件地址。
     */
    private String fileUrl;

    /**
     * AI 解析后的简历 JSON 数据。
     */
    private JsonNode parsedJson;

    /**
     * 简历状态。
     */
    private Integer status;

    /**
     * 创建时间。
     */
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    private LocalDateTime updateTime;
}