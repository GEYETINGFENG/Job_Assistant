package com.keny.jobassistant.model.document;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 简历最终解析结果。
 *
 * @param parsedJson       保存到数据库的 JSON
 * @param mediaType        文档真实媒体类型
 * @param extension        后端确定的文件扩展名
 * @param promptTokens     本次实际调用 LLM 消耗的输入 token；没有调用（本地解析或命中缓存）时为 null
 * @param completionTokens 本次实际调用 LLM 消耗的输出 token；没有调用时为 null
 */
public record ResumeParseResult(
        JsonNode parsedJson,
        String mediaType,
        String extension,
        Integer promptTokens,
        Integer completionTokens
) {

    /** 没有调用 LLM 的结果（本地解析）。 */
    public ResumeParseResult(JsonNode parsedJson, String mediaType, String extension) {
        this(parsedJson, mediaType, extension, null, null);
    }

    /** 命中缓存时返回：内容相同，但这次没有花 token，不能把上一次的用量再算一遍。 */
    public ResumeParseResult withoutUsage() {
        return new ResumeParseResult(parsedJson, mediaType, extension, null, null);
    }
}
