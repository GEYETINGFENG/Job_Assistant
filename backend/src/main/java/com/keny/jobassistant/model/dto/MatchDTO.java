package com.keny.jobassistant.model.dto;

import com.keny.jobassistant.model.ai.MatchAnalysis;
import com.keny.jobassistant.model.enums.MatchEngine;
import com.keny.jobassistant.model.enums.MatchStatus;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 匹配任务返回对象。PENDING / PROCESSING 时 score 和 analysis 为 null。
 */
@Data
public class MatchDTO implements Serializable {

    private Long id;
    private MatchStatus status;
    private MatchEngine engine;
    private Long jobId;
    private Long resumeId;
    private Long resumeVersionId;
    private Integer resumeVersionNumber;
    private Integer score;
    private MatchAnalysis analysis;
    private Integer attemptCount;
    private String lastErrorCode;
    private String lastErrorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
