package com.keny.jobassistant.controller;

import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ResultUtils;
import com.keny.jobassistant.model.dto.PresignResumeUploadResponse;
import com.keny.jobassistant.model.dto.ResumeUploadCompleteResponse;
import com.keny.jobassistant.model.dto.ResumeUploadStatusResponse;
import com.keny.jobassistant.model.entity.request.PresignResumeUploadRequest;
import com.keny.jobassistant.model.enums.ResumeUploadStatus;
import com.keny.jobassistant.service.ResumeS3UploadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * S3 简历直传接口。
 */
@RestController
@RequestMapping("/resumes")
public class ResumeUploadController {

    private final ResumeS3UploadService resumeS3UploadService;

    public ResumeUploadController(ResumeS3UploadService resumeS3UploadService) {
        this.resumeS3UploadService = resumeS3UploadService;
    }

    /**
     * 申请 S3 PUT 预签名 URL。
     */
    @PostMapping("/uploads/presign")
    public BaseResponse<PresignResumeUploadResponse> createPresignedUpload(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PresignResumeUploadRequest request) {
        return ResultUtils.success(resumeS3UploadService.createPresignedUpload(idempotencyKey, request));
    }

    /**
     * 申请已有简历新版本的 S3 PUT 预签名 URL。
     * 上传完成后不会创建新的 resume_id，而是创建 V2、V3 等版本。
     */
    @PostMapping("/{resumeId}/versions/uploads/presign")
    public BaseResponse<PresignResumeUploadResponse> createPresignedVersionUpload(
            @PathVariable Long resumeId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PresignResumeUploadRequest request) {
        return ResultUtils.success(resumeS3UploadService.createPresignedVersionUpload(resumeId,  idempotencyKey,request));
    }

    /**
     * 客户端完成 S3 PUT 后调用。
     * 后端冻结上传对象并加入处理队列；后台完成前返回 202，已经完成则返回 200。
     */
    @PostMapping("/uploads/{uploadId}/complete")
    public ResponseEntity<BaseResponse<ResumeUploadCompleteResponse>> completeUpload(@PathVariable UUID uploadId) {
        ResumeUploadCompleteResponse response = resumeS3UploadService.completeUpload(uploadId);
        // 排队和处理中都属于异步受理；只有后台已经完成时才返回 200。
        HttpStatus httpStatus = response.status() == ResumeUploadStatus.COMPLETED ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity
                .status(httpStatus)
                .body(ResultUtils.success(response));
    }

    /** 客户端收到 202 后使用该接口轮询，直到任务进入 COMPLETED 或 DEAD。 */
    @GetMapping("/uploads/{uploadId}")
    public BaseResponse<ResumeUploadStatusResponse> getUploadStatus(@PathVariable UUID uploadId) {
        return ResultUtils.success(resumeS3UploadService.getUploadStatus(uploadId));
    }
}
