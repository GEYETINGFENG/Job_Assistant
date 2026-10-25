package com.keny.jobassistant.service.rag;

import com.keny.jobassistant.model.ai.EvidenceSnippet;
import com.keny.jobassistant.model.ai.RequirementEvidence;
import com.keny.jobassistant.repository.ResumeChunkRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 为 JD 的每条要求在简历版本里检索证据。
 *
 * 向量检索总会返回 top-k，哪怕没有任何相关内容；不设阈值就等于让下游拿不相关的片段当证据。
 * 阈值按模型校准：不同模型的相似度分布完全不同（哈希向量的相关片段多在 0.1–0.4，text-embedding-v4 在 0.4–0.7），
 * 不能共用一个数。
 */
@Service
public class ResumeRetriever {

    static final int MAX_EXCERPT_LENGTH = 280;

    /** 每个 embedding 模型在评测集上校准出的 top-k 和相似度阈值。 */
    record Calibration(int topK, double similarityThreshold) {
    }

    /**
     * 阈值按 Youden J 选出；k 取召回不再提升的最小值（docs/rag-evaluation.md 实验 2、4、5c、5d）。
     * 没校准过的模型启动时直接失败，必须先跑评测或显式配置 RAG_TOP_K 和 RAG_SIMILARITY_THRESHOLD，避免沿用别的模型的参数。
     */
    static final Map<String, Calibration> CALIBRATIONS = Map.of(
            HashingEmbeddingClient.MODEL, new Calibration(3, 0.15),
            "text-embedding-v4", new Calibration(2, 0.35));

    private final ResumeChunkRepository chunkRepository;
    private final EmbeddingClient embeddingClient;
    private final RequirementExtractor requirementExtractor;
    private final int topK;
    private final double similarityThreshold;

    public ResumeRetriever(ResumeChunkRepository chunkRepository,
                           EmbeddingClient embeddingClient,
                           RequirementExtractor requirementExtractor,
                           @Value("${app.rag.top-k:#{null}}") Integer topKOverride,
                           @Value("${app.rag.similarity-threshold:#{null}}") Double similarityThresholdOverride) {
        this.chunkRepository = chunkRepository;
        this.embeddingClient = embeddingClient;
        this.requirementExtractor = requirementExtractor;
        Calibration calibration = calibration(embeddingClient.model(), topKOverride, similarityThresholdOverride);
        this.topK = calibration.topK();
        this.similarityThreshold = calibration.similarityThreshold();
    }

    /** 显式配置优先；两个参数都配置了时，未校准的模型也可以使用。 */
    static Calibration calibration(String model, Integer topKOverride, Double similarityThresholdOverride) {
        Calibration calibrated = CALIBRATIONS.get(model);
        if (calibrated == null && (topKOverride == null || similarityThresholdOverride == null)) {
            throw new IllegalStateException("No calibrated top-k / similarity threshold for embedding model " + model
                    + "; run RagEvaluationTest with this model or set RAG_TOP_K and RAG_SIMILARITY_THRESHOLD");
        }
        int topK = topKOverride != null ? topKOverride : calibrated.topK();
        double threshold = similarityThresholdOverride != null ? similarityThresholdOverride : calibrated.similarityThreshold();
        return new Calibration(Math.max(1, topK), threshold);
    }

    public List<RequirementEvidence> retrieve(Long resumeVersionId, String jobDescription) {
        List<String> requirements = requirementExtractor.extract(jobDescription);
        if (requirements.isEmpty()) {
            return List.of();
        }
        List<float[]> queryVectors = embeddingClient.embed(requirements);
        List<RequirementEvidence> result = new ArrayList<>(requirements.size());
        for (int i = 0; i < requirements.size(); i++) {
            List<EvidenceSnippet> evidence = chunkRepository
                    .search(resumeVersionId, embeddingClient.model(), queryVectors.get(i), topK)
                    .stream()
                    .filter(hit -> hit.similarity() >= similarityThreshold)
                    .map(hit -> new EvidenceSnippet(hit.section().name(), excerpt(hit.content()), round(hit.similarity())))
                    .toList();
            result.add(new RequirementEvidence(requirements.get(i), !evidence.isEmpty(), evidence));
        }
        return result;
    }

    private static String excerpt(String content) {
        return content.length() <= MAX_EXCERPT_LENGTH ? content : content.substring(0, MAX_EXCERPT_LENGTH).strip() + "…";
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }
}
