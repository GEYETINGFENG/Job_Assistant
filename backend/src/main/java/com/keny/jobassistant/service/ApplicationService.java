package com.keny.jobassistant.service;

import com.keny.jobassistant.model.dto.ApplicationDTO;
import com.keny.jobassistant.model.entity.request.ApplicationCreateRequest;

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
}