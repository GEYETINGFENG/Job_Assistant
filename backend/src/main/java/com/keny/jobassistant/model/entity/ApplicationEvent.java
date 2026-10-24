package com.keny.jobassistant.model.entity;

import com.keny.jobassistant.model.enums.ApplicationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Application 状态变更事件。
 * 只追加不修改：application 保存当前状态，这张表保存状态是怎么一步步变过来的。
 */
@Entity
@Table(name = "application_event")
@Getter
@Setter
public class ApplicationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private Application application;

    /**
     * 创建申请时为 null。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private ApplicationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private ApplicationStatus toStatus;

    /**
     * 执行这次变更的用户 ID。只记录 ID，读取事件历史时不需要加载 User。
     */
    @Column(name = "operator_user_id", nullable = false)
    private Long operatorUserId;

    @Column(name = "note", length = 512)
    private String note;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;
}
