package com.keny.jobassistant.service.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 评测专用：按文本缓存向量。评测里同一个块、同一条查询会被反复 embedding（每个实验都重建索引），
 * 调用真实 API 时只算一次，既省额度也保证同一次评测里同一段文本的向量完全一致。
 */
final class CachingEmbeddingClient implements EmbeddingClient {

    private final EmbeddingClient delegate;
    private final Map<String, float[]> cache = new HashMap<>();
    private int calls;

    CachingEmbeddingClient(EmbeddingClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    @Override
    public synchronized List<float[]> embed(List<String> texts) {
        List<String> misses = new ArrayList<>(new LinkedHashSet<>(texts));
        misses.removeIf(cache::containsKey);
        if (!misses.isEmpty()) {
            calls++;
            List<float[]> vectors = delegate.embed(misses);
            for (int i = 0; i < misses.size(); i++) {
                cache.put(misses.get(i), vectors.get(i));
            }
        }
        return texts.stream().map(cache::get).toList();
    }

    /** 实际转发给底层模型的 embed 调用次数。 */
    int calls() {
        return calls;
    }
}
