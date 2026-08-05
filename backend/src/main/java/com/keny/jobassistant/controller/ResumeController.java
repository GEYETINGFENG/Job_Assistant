package com.keny.jobassistant.controller;

import com.keny.jobassistant.common.BaseResponse;
import com.keny.jobassistant.common.ResultUtils;
import com.keny.jobassistant.model.dto.ResumeDTO;
import com.keny.jobassistant.model.entity.request.ResumeCreateRequest;
import com.keny.jobassistant.service.ResumeFileStorageService;
import com.keny.jobassistant.service.ResumeS3UploadService;
import com.keny.jobassistant.service.ResumeService;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * 简历接口。
 *
 * 只提供创建简历和查询指定简历两个功能。
 */
@RestController
@RequestMapping("/resumes")
public class ResumeController {
    private final ResumeService resumeService;
    private final ResumeFileStorageService resumeFileStorageService;
    private final ResumeS3UploadService resumeS3UploadService;
    //MediaType 是 Spring 对 MIME 类型的封装，MIME 类型用于描述一个文件是什么格式
    private static final MediaType DOCX_MEDIA_TYPE =
            MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            ); //DOCX 官方 MIME 类型
    public ResumeController(ResumeService resumeService,ResumeFileStorageService resumeFileStorageService,ResumeS3UploadService resumeS3UploadService) {
        this.resumeService = resumeService;
        this.resumeFileStorageService = resumeFileStorageService;
        this.resumeS3UploadService = resumeS3UploadService;
    }
    /**
     * 为当前登录用户创建简历，用户身份从 JWT 获取，
     * 请求参数中不允许提交 userId。
     * fileUrl 由后端保存文件后生成，parsedJson 由后端解析文件后填充，
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    //一个 HTTP 请求里面包含多个不同类型的数据块
    public BaseResponse<Long> createResume(
            //从 multipart 请求中找到名字叫 request 的那一部分
            @RequestPart("request") ResumeCreateRequest request,
            @RequestPart("file") MultipartFile file
    ) {
        Long resumeId = resumeService.createResume(request, file);
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

    /**
     * 下载当前登录用户拥有的简历文件。
     * 先调用 getResume 校验资源归属，用户不能下载其他用户的简历文件。
     * S3 简历返回 302 跳转到短期预签名下载 URL；
     * 旧的本地简历继续使用原本的 Resource 下载逻辑。
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> downloadResumeFile(@PathVariable Long id) {
        ResumeDTO resume = resumeService.getResume(id);
        //尝试生成S3临时下载URL
        Optional<String> s3DownloadUrl = resumeS3UploadService.createDownloadUrlIfPresent(id);

        if (s3DownloadUrl.isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(s3DownloadUrl.get()))
                    .build();
        }
        Resource fileResource = resumeFileStorageService.loadResumeFile(id);

        String storedFilename = fileResource.getFilename();
        boolean isDocx = storedFilename != null && storedFilename.toLowerCase(Locale.ROOT).endsWith(".docx");
        String extension = isDocx ? ".docx" : ".pdf";
        MediaType contentType = isDocx ? DOCX_MEDIA_TYPE : MediaType.APPLICATION_PDF;
        String downloadFilename = resume.getResumeName() + extension;
        // 告诉浏览器这个文件应该叫什么名字,应该下载而不是打开
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(downloadFilename, StandardCharsets.UTF_8)
                .build();
        // 构造 HTTP 响应，把简历文件真正返回给客户端
        return ResponseEntity.ok()
                .contentType(contentType) //文件类型
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .body(fileResource);
    }
}