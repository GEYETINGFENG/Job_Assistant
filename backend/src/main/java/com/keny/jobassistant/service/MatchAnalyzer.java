package com.keny.jobassistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.keny.jobassistant.model.ai.MatchAnalysisResult;
import com.keny.jobassistant.model.enums.MatchEngine;

/**
 * 简历与 JD 的匹配分析。
 * LLM 开启时使用 BailianMatchAnalyzer，关闭时使用 KeywordMatchAnalyzer 兜底。
 */
public interface MatchAnalyzer {

    MatchEngine engine();

    /**
     * 模型版本；关键词兜底没有模型，返回 null。
     */
    String modelVersion();

    /**
     * 分析逻辑版本。prompt 或打分规则变化时提升版本，旧结果不会被当成新结果复用。
     */
    String promptVersion();

    /**
     * @param resumeContent  简历版本的结构化内容（resume_version.content_json）
     * @param jobTitle       JD 职位名称
     * @param jobDescription JD 原文，不可信数据
     */
    MatchAnalysisResult analyze(JsonNode resumeContent, String jobTitle, String jobDescription);
}
