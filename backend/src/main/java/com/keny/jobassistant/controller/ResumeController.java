package com.keny.jobassistant.controller;
import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.common.ResultUtils;
import com.keny.jobassistant.exception.BusinessException;
import com.keny.jobassistant.model.dto.ResumeDTO;
import com.keny.jobassistant.model.dto.ResumeVersionDTO;
import com.keny.jobassistant.model.dto.ResumeVersionSummaryDTO;
import com.keny.jobassistant.model.entity.request.ResumeUpdateRequest;
import com.keny.jobassistant.service.ResumeS3UploadService;
import com.keny.jobassistant.service.ResumeService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.List;
/**
 * 简历接口。
 *
 * 只提供创建简历和查询指定简历两个功能。
 */
@RestController
@RequestMapping("/resumes")
public class ResumeController {
    private final ResumeService resumeService;
    private final ResumeS3UploadService resumeS3UploadService;
    //MediaType 是 Spring 对 MIME 类型的封装，MIME 类型用于描述一个文件是什么格式
    private static final MediaType DOCX_MEDIA_TYPE =
            MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            ); //DOCX 官方 MIME 类型
    public ResumeController(ResumeService resumeService,ResumeS3UploadService resumeS3UploadService) {
        this.resumeService = resumeService;
        this.resumeS3UploadService = resumeS3UploadService;
    }

    /**
     * 查询当前登录用户拥有的指定简历。
     */
    @GetMapping("/{id}")
    public BaseResponse<ResumeDTO> getResume(@PathVariable Long id) {
        ResumeDTO resume = resumeService.getResume(id);
        return ResultUtils.success(resume);
    }

    /**
     * 查询某份简历的版本列表。
     */
    @GetMapping("/{resumeId}/versions")
    public BaseResponse<List<ResumeVersionSummaryDTO>> listResumeVersions(@PathVariable Long resumeId) {
        return ResultUtils.success(resumeService.listResumeVersions(resumeId));
    }
    /**
     * 查询某份简历的指定历史版本。
     */
    @GetMapping("/{resumeId}/versions/{versionNumber}")
    public BaseResponse<ResumeVersionDTO> getResumeVersion(@PathVariable Long resumeId, @PathVariable Integer versionNumber) {
        return ResultUtils.success(resumeService.getResumeVersion(resumeId, versionNumber));
    }

    /**
     * 下载当前登录用户拥有的简历文件。
     * 先调用 getResume 校验资源归属，用户不能下载其他用户的简历文件。
     * S3 简历返回 302 跳转到短期预签名下载 URL；
     * 旧的本地简历继续使用原本的 Resource 下载逻辑。
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<Void> downloadResumeFile(@PathVariable Long id) {
        ResumeDTO resume = resumeService.getResume(id);
        String downloadUrl = resumeS3UploadService
                .createDownloadUrlIfPresent(id, resume.getLatestVersionNumber())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Resume file does not exist in S3"));
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(downloadUrl))
                .build();
    }

    /**
     * 编辑当前用户拥有的简历信息。
     * 使用 lockVersion 防止并发修改导致数据覆盖。
     */
    @PatchMapping("/{id}")
    public BaseResponse<ResumeDTO> updateResume(@PathVariable Long id, @RequestBody ResumeUpdateRequest request) {
        return ResultUtils.success(resumeService.updateResume(id, request));
    }
    /**
     * 软删除 Resume。
     */
    @DeleteMapping("/{id}")
    public BaseResponse<Boolean> deleteResume(@PathVariable Long id) {
        return ResultUtils.success(resumeService.deleteResume(id));
    }
}