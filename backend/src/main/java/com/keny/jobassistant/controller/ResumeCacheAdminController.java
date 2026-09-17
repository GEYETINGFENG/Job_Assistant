package com.keny.jobassistant.controller;

import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ResultUtils;
import com.keny.jobassistant.model.dto.ResumeCacheStatsDTO;
import com.keny.jobassistant.service.ResumeParseCacheService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 复用 /admin/** 的管理员权限规则。 */
@RestController
@RequestMapping("/admin/resume-cache")
public class ResumeCacheAdminController {
    private final ResumeParseCacheService cacheService;

    public ResumeCacheAdminController(ResumeParseCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @GetMapping("/stats")
    public BaseResponse<ResumeCacheStatsDTO> stats() {
        return ResultUtils.success(cacheService.stats());
    }
}
