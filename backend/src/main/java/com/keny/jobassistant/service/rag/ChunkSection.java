package com.keny.jobassistant.service.rag;

/**
 * 简历块来自哪个段落。TEXT 表示没有结构化内容、按固定长度切出的原文块。
 */
public enum ChunkSection {
    SUMMARY,
    SKILLS,
    EXPERIENCE,
    PROJECT,
    EDUCATION,
    CERTIFICATE,
    TEXT
}
