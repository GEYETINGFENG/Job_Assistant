package com.keny.jobassistant.controller;

import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.common.ResultUtils;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.dto.ApplicationDTO;
import com.keny.jobassistant.model.entity.request.ApplicationCreateRequest;
import com.keny.jobassistant.service.ApplicationService;
import com.keny.jobassistant.service.ResumeS3UploadService;
import jakarta.annotation.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/applications")
public class ApplicationController {

    @Resource
    private ApplicationService applicationService;

    @Resource
    private ResumeS3UploadService resumeS3UploadService;

    /**
     * 创建 Application
     * 请求必须明确传入本次使用的 ResumeVersion。
     */
    @PostMapping
    public BaseResponse<ApplicationDTO> createApplication(@RequestBody ApplicationCreateRequest request) {
        return ResultUtils.success(applicationService.createApplication(request));
    }

    /**
     * 查询当前用户的一条 Application。
     */
    @GetMapping("/{id}")
    public BaseResponse<ApplicationDTO> getApplication(@PathVariable Long id) {
        return ResultUtils.success(applicationService.getApplication(id));
    }

    /**
     * 查询当前用户全部 Application。
     */
    @GetMapping
    public BaseResponse<List<ApplicationDTO>> listApplications() {
        return ResultUtils.success(applicationService.listApplications());
    }

    /**
     * 下载某条 Application 投递时实际使用的 ResumeVersion。
     * 这里不能检查 Resume.isDelete
     * Resume 即使后来被用户软删除，Application 作为历史记录仍然应该能够访问
     * 投递时使用的具体 ResumeVersion。
     */
    @GetMapping("/{id}/resume")
    public ResponseEntity<Void> downloadApplicationResume(@PathVariable Long id) {
        ApplicationDTO application = applicationService.getApplication(id);

        if (application.getResumeId() == null || application.getResumeVersionNumber() == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "This application does not have a resume version");
        }
        String downloadUrl = resumeS3UploadService
                .createDownloadUrlIfPresent(application.getResumeId(), application.getResumeVersionNumber())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Application resume file does not exist in S3"));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(downloadUrl))
                .build();
    }
}