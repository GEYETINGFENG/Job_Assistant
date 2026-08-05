package com.keny.jobassistant.model.entity;

import com.keny.jobassistant.model.enums.ResumeUploadStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * S3 简历上传会话。
 * 一条记录代表一次预签名上传流程。
 * objectKey 只能由后端生成，客户端不能自行指定。
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

    /**
     * S3 对象 Key。
     *
     * 上传完成前保存临时 Key；
     * 处理成功后更新为正式 Key。
     */
    @Column(name = "object_key", nullable = false, unique = true, length = 512)
    private String objectKey;

    @Column(name = "expected_extension", nullable = false, length = 10)
    private String expectedExtension;

    @Column(name = "expected_content_type", nullable = false, length = 128)
    private String expectedContentType;

    @Column(name = "expected_size", nullable = false)
    private Long expectedSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ResumeUploadStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "resume_id")
    private Long resumeId;

    @Column(name = "create_time", nullable = false)
    private Instant createTime;

    @Column(name = "update_time", nullable = false)
    private Instant updateTime;
}