package com.keny.jobassistant.service;
import com.keny.jobassistant.model.document.ResumeParseResult;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历解析服务。
 */
public interface ResumeParserService {

    /**
     * 检测、提取并解析简历。
     * @param file 客户端上传的简历文件
     * @return 简历解析结果
     */
    ResumeParseResult parseResume(MultipartFile file);
}