package com.keny.jobassistant.model.dto;

import com.keny.jobassistant.model.enums.ApplicationStatus;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Application 状态变更事件返回对象。
 */
@Data
public class ApplicationEventDTO implements Serializable {

    private Long id;

    /**
     * 创建申请的事件没有来源状态。
     */
    private ApplicationStatus fromStatus;
    private ApplicationStatus toStatus;
    private Long operatorUserId;
    private String note;
    private LocalDateTime createTime;
}
