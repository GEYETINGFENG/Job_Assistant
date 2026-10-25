package com.keny.jobassistant.model.ai;

/**
 * 简历中支持某条 JD 要求的一个片段。
 *
 * @param section    来源段落，例如 EXPERIENCE、PROJECT
 * @param excerpt    片段文本（截断后的块内容）
 * @param similarity 与要求的余弦相似度，越大越相关
 */
public record EvidenceSnippet(String section, String excerpt, double similarity) {
}
