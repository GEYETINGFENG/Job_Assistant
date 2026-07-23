package com.keny.jobassistant.controller;

import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ResultUtils;
import com.keny.jobassistant.model.dto.ResumeDTO;
import com.keny.jobassistant.model.entity.request.ResumeCreateRequest;
import com.keny.jobassistant.service.ResumeService;
import org.springframework.web.bind.annotation.*;

/**
 * 简历接口。
 *
 * 只提供创建简历和查询指定简历两个功能。
 */
@RestController
@RequestMapping("/resumes")
public class ResumeController {
    private final ResumeService resumeService;
    public ResumeController(ResumeService resumeService) {
        this.resumeService = resumeService;
    }
    /**
     * 为当前登录用户创建简历，用户身份从 JWT 获取，
     * 请求参数中不允许提交 userId。
     */
    @PostMapping
    public BaseResponse<Long> createResume(@RequestBody ResumeCreateRequest request) {
        Long resumeId = resumeService.createResume(request);
        return ResultUtils.success(resumeId);
    }

    /**
     * 查询当前登录用户拥有的指定简历。
     */
    @GetMapping("/{id}")
    public BaseResponse<ResumeDTO> getResume(@PathVariable Long id) {
        ResumeDTO resume = resumeService.getResume(id);
        return ResultUtils.success(resume);
    }
}