package com.keny.jobassistant.service;
import com.keny.jobassistant.model.dto.ResumeDTO;
import com.keny.jobassistant.model.dto.ResumeVersionDTO;
import com.keny.jobassistant.model.dto.ResumeVersionSummaryDTO;

import java.util.List;

/**
 * 简历服务接口。
 */
public interface ResumeService {
    /**
     * 查询当前登录用户拥有的指定简历。
     * @param resumeId 简历 ID
     * @return 简历信息
     */
    ResumeDTO getResume(Long resumeId);

    /**
     * 查询指定简历的全部历史版本。
     */
    List<ResumeVersionSummaryDTO> listResumeVersions(Long resumeId);

    /**
     * 查询指定简历的某个历史版本。
     */
    ResumeVersionDTO getResumeVersion(Long resumeId, Integer versionNumber);
}