package com.keny.jobassistant.model.ai;

import java.util.List;

/**
 * 一条 JD 要求及其在简历中检索到的证据。
 *
 * @param covered  最相关片段的相似度达到阈值；低于阈值的片段不作为证据，避免拿不相关的内容充数
 * @param evidence 达到阈值的片段，最相关的在前
 */
public record RequirementEvidence(String requirement, boolean covered, List<EvidenceSnippet> evidence) {
}
