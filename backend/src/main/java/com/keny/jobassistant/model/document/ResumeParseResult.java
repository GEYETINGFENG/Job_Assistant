package com.keny.jobassistant.model.document;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 简历最终解析结果。
 *
 * @param parsedJson 保存到数据库的 JSON
 * @param mediaType 文档真实媒体类型
 * @param extension 后端确定的文件扩展名
 */
public record ResumeParseResult(
        JsonNode parsedJson,
        String mediaType,
        String extension
) {
}