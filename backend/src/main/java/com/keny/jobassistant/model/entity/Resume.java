package com.keny.jobassistant.model.entity;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;

/**
 * 用户简历实体。
 */
@Entity
@Table(name = "resume")
@Getter
@Setter
public class Resume {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 简历所属用户。
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 简历名称。
     */
    @Column(name = "resume_name")
    private String resumeName;

    /**
     * 简历文件地址。
     */
    @Column(name = "file_url")
    private String fileUrl;

    /**
     * AI 解析后的简历 JSON 数据。
     * 使用 Hibernate JSON 类型映射到 PostgreSQL JSONB。
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parsed_json", columnDefinition = "jsonb")
    private JsonNode parsedJson;

    /**
     * 简历状态。
     */
    @Column(name = "status")
    private Integer status = 0;

    /**
     * 创建时间。
     */
    @Column(name = "create_time")
    private LocalDateTime createTime;

    /**
     * 更新时间。
     */
    @Column(name = "update_time")
    private LocalDateTime updateTime;
}