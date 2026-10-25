package com.keny.jobassistant.model.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.keny.jobassistant.model.enums.MatchEngine;
import com.keny.jobassistant.model.enums.MatchStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 简历版本与 JD 的匹配任务及结果。
 * 同一版本 + 同一 JD + 同一分析方式只有一条记录（uk_match_version_job）。
 */
@Entity
@Table(name = "match_record")
@Getter
@Setter
public class MatchRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 发起匹配的用户，用于归属校验和 LLM 用量归因。
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 冗余的简历 ID，方便展示；匹配对象以 resumeVersion 为准。
     */
    @Column(name = "resume_id", nullable = false)
    private Long resumeId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resume_version_id", nullable = false)
    private ResumeVersion resumeVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MatchStatus status = MatchStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "engine", nullable = false, length = 16)
    private MatchEngine engine;

    @Column(name = "model_version", length = 128)
    private String modelVersion;

    @Column(name = "prompt_version", nullable = false, length = 64)
    private String promptVersion;

    /**
     * 0–100，只有 COMPLETED 时有值。
     */
    @Column(name = "score")
    private Integer score;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "analysis_json", columnDefinition = "jsonb")
    private JsonNode analysisJson;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    /**
     * 当前领取这条任务的 worker 凭证；提交结果时必须一致，防止超时的旧 worker 覆盖新结果。
     */
    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = 512)
    private String lastErrorMessage;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;
}
