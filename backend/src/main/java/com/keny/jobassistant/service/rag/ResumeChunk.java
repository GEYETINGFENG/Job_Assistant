package com.keny.jobassistant.service.rag;

/**
 * 简历切出的一块。
 *
 * @param key     同一版本内确定且唯一，例如 experience:0、project:1、skills；评测集用它标注 ground truth
 * @param ordinal 块在简历中的顺序
 * @param content 送去 embedding 的文本，带段落标签
 */
public record ResumeChunk(String key, ChunkSection section, int ordinal, String content) {
}
