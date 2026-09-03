package com.keny.jobassistant.model.entity;

import com.keny.jobassistant.model.enums.ResumeUploadStatus;
import com.keny.jobassistant.model.enums.ResumeUploadType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * S3 简历上传会话。
 * 一条记录代表一次预签名上传流程。
 * 所有 S3 object key 只能由后端生成，客户端不能自行指定。
 */
@Getter
@Setter
@Entity
@Table(name = "resume_upload_session")
public class ResumeUploadSession {

    @Id
    private UUID id;

    /**
     * 上传会话所属用户。
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "resume_name", nullable = false, length = 256)
    private String resumeName;

    @Column(name = "original_filename", nullable = false, length = 256)
    private String originalFilename;

    /** 客户端通过预签名 URL 写入的临时对象。 */
    @Column(name = "upload_object_key", length = 512)
    private String uploadObjectKey;

    /** complete 接口冻结出的客户端不可写对象，worker 始终处理这一份内容。 */
    @Column(name = "processing_object_key", length = 512)
    private String processingObjectKey;

    /** 校验和解析成功后长期保存的最终对象。 */
    @Column(name = "final_object_key", length = 512)
    private String finalObjectKey;

    @Column(name = "expected_extension", nullable = false, length = 10)
    private String expectedExtension;

    @Column(name = "expected_content_type", nullable = false, length = 128)
    private String expectedContentType;

    @Column(name = "expected_size", nullable = false)
    private Long expectedSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ResumeUploadStatus status;

    /**
     * 上传类型。
     * CREATE：创建新简历。 NEW_VERSION：给已有简历增加新版本。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "upload_type", nullable = false, length = 20)
    private ResumeUploadType uploadType;

    /**
     * 本次上传最终创建出来的版本号。
     */
    @Column(name = "version_number")
    private Integer versionNumber;

    /** 客户端提供的幂等键，CREATE 和 NEW_VERSION 都用它避免重复创建业务任务。 */
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "resume_id")
    private Long resumeId;

    /**
     * worker 已经领取并开始执行的次数。
     */
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount = 0;

    /**
     * PENDING 任务最早可以再次领取的时间。
     */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /**
     * 当前处理尝试的开始时间，用于恢复超时的 PROCESSING 任务。
     */
    @Column(name = "processing_started_at")
    private Instant processingStartedAt;

    /**
     * 当前处理尝试的唯一标识，防止过期 worker 提交结果。
     */
    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = 512)
    private String lastErrorMessage;

    @Column(name = "create_time", nullable = false)
    private Instant createTime;

    @Column(name = "update_time", nullable = false)
    private Instant updateTime;
}
