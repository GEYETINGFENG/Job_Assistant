package com.keny.jobassistant.service.rag;

import java.util.List;

/**
 * 文本向量化。不同模型的向量不在同一个语义空间，存储和检索都必须按 model() 区分。
 */
public interface EmbeddingClient {

    /**
     * 模型标识，写入 resume_chunk.embedding_model。换模型后旧向量不能与新向量混用。
     */
    String model();

    int dimensions();

    /**
     * 返回与 texts 一一对应的向量，长度为 dimensions()，已做 L2 归一化。
     */
    List<float[]> embed(List<String> texts);
}
