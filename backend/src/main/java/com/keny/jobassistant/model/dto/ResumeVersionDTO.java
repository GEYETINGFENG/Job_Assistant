package com.keny.jobassistant.model.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 简历版本详情 DTO。
 */
@Data
public class ResumeVersionDTO implements Serializable {

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
     * 该版本的 AI 解析 JSON 快照。
     */
    private JsonNode contentJson;

    /**
     * 版本创建时间。
     */
    private LocalDateTime createTime;
}