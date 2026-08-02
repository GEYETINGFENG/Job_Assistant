package com.keny.jobassistant.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keny.jobassistant.service.PdfTextExtractor;
import com.keny.jobassistant.service.ResumeParserService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 本地 PDF 文本解析实现。
 * 未启用 AI 时使用该实现，方便开发和测试文件上传流程。
 */
@Service
@ConditionalOnProperty(
        prefix = "app.resume.ai",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class PdfTextResumeParserServiceImpl implements ResumeParserService {

    private final PdfTextExtractor pdfTextExtractor;
    private final ObjectMapper objectMapper; // Java对象 与 JSON互相转换的工具

    public PdfTextResumeParserServiceImpl(
            PdfTextExtractor pdfTextExtractor,
            ObjectMapper objectMapper
    ) {
        this.pdfTextExtractor = pdfTextExtractor;
        this.objectMapper = objectMapper;
    }

    /**
     * 把 PDF 简历里面的文字提取出来，然后包装成一个 JSON 对象返回
     */
    @Override
    public JsonNode parseResume(MultipartFile file) {
        String resumeText = pdfTextExtractor.extractText(file);
        // 创建一个 JSON 对象
        ObjectNode parsedJson = objectMapper.createObjectNode();
        parsedJson.put("parserMode", "pdf-text");// 添加字段：当前解析方式
        parsedJson.put("rawText", resumeText);//  把刚刚提取出来的文本放进去
        return parsedJson;
    }
}