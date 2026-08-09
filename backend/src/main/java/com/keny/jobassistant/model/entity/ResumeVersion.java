package com.keny.jobassistant.model.entity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 简历历史版本实体。
 *
 * 每次创建新简历或者上传新版本时，
 * 都会在 resume_version 表中生成一条历史快照。
 */
@Entity
@Table(name = "resume_version")
@Getter
@Setter
public class ResumeVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 历史版本所属的简历。
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resume_id", nullable = false)
    private Resume resume;

    /**
     * 版本号，例如 V1 对应 1，V2 对应 2。
     */
    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    /**
     * 该版本对应的 AI 解析 JSON 快照。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_json", columnDefinition = "jsonb")
    private JsonNode contentJson;

    /**
     * 版本创建时间。
     */
    @Column(name = "create_time")
    private LocalDateTime createTime;
}