package com.keny.jobassistant.service;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历解析服务。
 */
public interface ResumeParserService {

    /**
     * 解析简历文件。
     * @param file 客户端上传的简历文件
     * @return 解析后的 JSON 数据
     */
    JsonNode parseResume(MultipartFile file);
}