package com.keny.jobassistant.model.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "application")
@Getter
@Setter
public class Application {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 当前申请所属用户。
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 当前申请对应的岗位。
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    /**
     * 投递这个岗位时实际使用的 ResumeVersion
     * 这样即使用户之后上传 V3、V4，历史申请依然知道当时使用的是 V2
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_version_id")
    private ResumeVersion resumeVersion;

    @Column(name = "status")
    private Integer status = 0;

    @Column(name = "apply_time")
    private LocalDateTime applyTime;
}