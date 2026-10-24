package com.keny.jobassistant.service;

import com.keny.jobassistant.model.dto.ApplicationDTO;
import com.keny.jobassistant.model.dto.ApplicationEventPageDTO;
import com.keny.jobassistant.model.entity.request.ApplicationCreateRequest;
import com.keny.jobassistant.model.entity.request.ApplicationStatusUpdateRequest;

import java.util.List;

public interface ApplicationService {

    /**
     * 创建 Application。
     * 必须明确指定本次申请使用的 ResumeVersion。
     */
    ApplicationDTO createApplication(ApplicationCreateRequest request);

    /**
     * 查询当前用户自己的 Application。
     */
    ApplicationDTO getApplication(Long applicationId);

    /**
     * 查询当前用户全部 Application。
     */
    List<ApplicationDTO> listApplications();

    /**
     * 修改申请状态。
     * 只允许转移表中定义的转移，并且数据库中的当前状态必须仍然等于 request.fromStatus。
     */
    ApplicationDTO updateStatus(Long applicationId, ApplicationStatusUpdateRequest request);

    /**
     * 游标分页查询申请的状态变更历史，最新的在前。
     *
     * @param cursor 上一页返回的 nextCursor；查询第一页时为 null
     */
    ApplicationEventPageDTO listEvents(Long applicationId, Long cursor, int size);
}
