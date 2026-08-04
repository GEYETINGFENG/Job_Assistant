package com.keny.jobassistant.service.impl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keny.jobassistant.model.document.ResumeDocumentContent;
import com.keny.jobassistant.model.document.ResumeParseResult;
import com.keny.jobassistant.service.ResumeParserService;
import com.keny.jobassistant.service.TikaResumeDocumentExtractor;
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
public class TikaTextResumeParserServiceImpl implements ResumeParserService {
    private final TikaResumeDocumentExtractor documentExtractor;
    private final ObjectMapper objectMapper; // Java对象 与 JSON互相转换的工具
    public TikaTextResumeParserServiceImpl(
            TikaResumeDocumentExtractor documentExtractor,
            ObjectMapper objectMapper
    ) {
        this.documentExtractor = documentExtractor;
        this.objectMapper = objectMapper;
    }

    /**
     * 使用 Tika 检测文件并提取正文
     */
    @Override
    public ResumeParseResult parseResume(MultipartFile file) {
        ResumeDocumentContent document = documentExtractor.extract(file);
        // 创建一个 JSON 对象
        ObjectNode parsedJson = objectMapper.createObjectNode();
        parsedJson.put("parserMode", "tika");
        parsedJson.put("mediaType", document.mediaType());
        parsedJson.put("rawText", document.text());
        return new ResumeParseResult(parsedJson, document.mediaType(), document.extension());
    }
}