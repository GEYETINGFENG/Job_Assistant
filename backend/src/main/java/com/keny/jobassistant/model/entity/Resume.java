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
 * resume 表保存简历当前最新版本的数据，
 * 历史版本保存在 resume_version 表中
 */
@Entity
@Table(name = "resume")
@Getter
@Setter
public class Resume {
    public static final int NOT_DELETED = 0;
    public static final int DELETED = 1;

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
     * 当前最新版本号。
     *  0 仅表示该 Resume 尚未成功创建任何版本，不代表存在 V0。
     * 创建第一份简历时，会通过原子 SQL 将 0 增加为 1，因此用户能够看到的第一个有效版本始终是 V1。
     */
    @Column(name = "latest_version_number", nullable = false)
    private Integer latestVersionNumber = 0;

    /**
     * JPA 乐观锁版本号。
     * 每次通过 JPA 修改 Resume 时由 Hibernate 自动增加，
     * 用于检测两个事务同时修改同一条 Resume 的情况。
     */
    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    /**
     * 软删除标记。
     */
    @Column(name = "is_delete", nullable = false)
    private Integer isDelete = NOT_DELETED;

    /**
     * 软删除时间。
     */
    @Column(name = "delete_time")
    private LocalDateTime deleteTime;

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