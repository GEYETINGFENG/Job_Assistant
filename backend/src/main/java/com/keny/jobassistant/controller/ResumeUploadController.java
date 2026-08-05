package com.keny.jobassistant.controller;

import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ResultUtils;
import com.keny.jobassistant.model.dto.PresignResumeUploadResponse;
import com.keny.jobassistant.model.entity.request.PresignResumeUploadRequest;
import com.keny.jobassistant.service.ResumeS3UploadService;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * S3 简历直传接口。
 */
@RestController
@RequestMapping("/resumes/uploads")
public class ResumeUploadController {

    private final ResumeS3UploadService resumeS3UploadService;

    public ResumeUploadController(ResumeS3UploadService resumeS3UploadService) {
        this.resumeS3UploadService = resumeS3UploadService;
    }

    /**
     * 申请 S3 PUT 预签名 URL。
     */
    @PostMapping("/presign")
    public BaseResponse<PresignResumeUploadResponse> createPresignedUpload(
            @RequestBody PresignResumeUploadRequest request) {
        return ResultUtils.success(resumeS3UploadService.createPresignedUpload(request));
    }

    /**
     * 客户端完成 S3 PUT 后调用。
     *
     * 后端会主动验证 S3 对象、执行 Tika 检查并创建 Resume。
     */
    @PostMapping("/{uploadId}/complete")
    public BaseResponse<Long> completeUpload(@PathVariable UUID uploadId) {
        return ResultUtils.success(resumeS3UploadService.completeUpload(uploadId));
    }
}