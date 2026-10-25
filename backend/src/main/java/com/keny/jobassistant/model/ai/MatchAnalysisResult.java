package com.keny.jobassistant.model.ai;

/**
 * 一次匹配分析的输出：分数、分析内容和 LLM 用量（关键词兜底没有用量，为 null）。
 */
public record MatchAnalysisResult(
        int score,
        MatchAnalysis analysis,
        Integer promptTokens,
        Integer completionTokens
) {
}
