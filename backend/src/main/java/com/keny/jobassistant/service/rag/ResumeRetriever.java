package com.keny.jobassistant.service.rag;

import com.keny.jobassistant.model.ai.EvidenceSnippet;
import com.keny.jobassistant.model.ai.RequirementEvidence;
import com.keny.jobassistant.repository.ResumeChunkRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 为 JD 的每条要求在简历版本里检索证据。
 *
 * 向量检索总会返回 top-k，哪怕没有任何相关内容；不设阈值就等于让下游拿不相关的片段当证据。
 * 阈值按模型校准：local-hash-v1 的 0.15 来自评测集上的阈值扫描（docs/rag-evaluation.md 实验 4）。
 */
@Service
public class ResumeRetriever {

    static final int MAX_EXCERPT_LENGTH = 280;

    private final ResumeChunkRepository chunkRepository;
    private final EmbeddingClient embeddingClient;
    private final RequirementExtractor requirementExtractor;
    private final int topK;
    private final double similarityThreshold;

    public ResumeRetriever(ResumeChunkRepository chunkRepository,
                           EmbeddingClient embeddingClient,
                           RequirementExtractor requirementExtractor,
                           @Value("${app.rag.top-k:3}") int topK,
                           @Value("${app.rag.similarity-threshold:0.15}") double similarityThreshold) {
        this.chunkRepository = chunkRepository;
        this.embeddingClient = embeddingClient;
        this.requirementExtractor = requirementExtractor;
        this.topK = Math.max(1, topK);
        this.similarityThreshold = similarityThreshold;
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
