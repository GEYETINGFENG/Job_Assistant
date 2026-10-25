package com.keny.jobassistant.model.enums;

/**
 * 匹配分析方式。
 */
public enum MatchEngine {

    // 调用 LLM 分析。
    LLM,

    // LLM 关闭时的关键词兜底：确定性、免费，但只能比对技能关键词。
    KEYWORD
}
