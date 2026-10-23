package com.keny.jobassistant.model.entity.request;

import com.keny.jobassistant.model.enums.ApplicationStatus;
import lombok.Data;

/**
 * 修改申请状态请求。
 */
@Data
public class ApplicationStatusUpdateRequest {

    /**
     * 客户端看到的当前状态（例如看板上卡片所在的列）。
     * 服务端只在数据库中的状态仍然等于它时才修改，
     * 防止基于过期状态做出的决定覆盖别人的修改。
     */
    private ApplicationStatus fromStatus;

    /**
     * 目标状态。
     */
    private ApplicationStatus toStatus;

    /**
     * 可选备注，例如被拒原因。
     */
    private String note;
}
