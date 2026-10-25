package com.keny.jobassistant.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检索评测：在带人工标注的数据集上计算 Recall@k、Precision@k、MRR，以及相似度阈值的取舍。
 *
 * 为了让不同切块策略可以比较，所有策略都在同一份"规范文本"上计算位置：
 * 规范文本 = 结构化块按顺序用空行拼接；标注的是结构化块的 key，
 * 固定长度切出的块只要与标注段落的重叠超过两者中较短者的一半，就算命中该段落。
 * Recall 按"标注段落被找回的比例"计算，与块的大小无关。
 */
final class RagEvaluator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param hard 难负样本：与简历同一领域，但点名的具体技术简历里没有（例如 ML 简历对 "Hadoop and Hive"）。
     *             语义向量会觉得它们"挺像"，阈值必须能拒绝这类查询。
     */
    record Query(String resume, String query, List<String> relevant, boolean hard) {
        boolean positive() {
            return !relevant.isEmpty();
        }
    }

    record SpannedChunk(String key, String content, int start, int end) {
    }

    interface ChunkStrategy {
        String name();

        List<SpannedChunk> chunk(CanonicalResume resume);
    }

    /** 结构化块拼成的规范文本，以及每个结构化块在其中的位置（即标注的位置）。 */
    record CanonicalResume(String text, List<SpannedChunk> structuredChunks) {
        Map<String, SpannedChunk> spansByKey() {
            Map<String, SpannedChunk> spans = new HashMap<>();
            structuredChunks.forEach(chunk -> spans.put(chunk.key(), chunk));
            return spans;
        }
    }

    record Metrics(double recall, double precision, double mrr, int queries) {
    }

    /**
     * 生产中的取法：top-k 之后再丢掉低于阈值的片段。
     * @param precision        留下的片段里相关的比例（只统计至少留下一个片段的查询）
     * @param evidencePerQuery 平均每条要求实际交给下游的片段数
     */
    record FilteredMetrics(double recall, double precision, double evidencePerQuery) {
    }

    /**
     * @param recall           标注段落被找回的比例
     * @param contextPrecision 取回的字符中属于相关段落的比例：越低说明塞进 prompt 的无关内容越多
     * @param charsPerQuery    平均每条查询取回的字符数
     */
    record BudgetMetrics(double recall, double contextPrecision, double charsPerQuery) {
    }

    record ThresholdResult(double threshold, double positiveRate, double negativeRate, double hardNegativeRate) {
        double youden() {
            return positiveRate + negativeRate - 1;
        }
    }

    record QueryOutcome(Query query, List<String> topKeys, double topSimilarity, boolean hit) {
    }

    private final Map<String, CanonicalResume> resumes;
    private final List<Query> queries;

    private RagEvaluator(Map<String, CanonicalResume> resumes, List<Query> queries) {
        this.resumes = resumes;
        this.queries = queries;
    }

    static RagEvaluator loadDefaultDataset() throws IOException {
        JsonNode resumeNodes = read("/rag-eval/resumes.json");
        List<Query> queries = new ArrayList<>();
        for (JsonNode node : read("/rag-eval/queries.json")) {
            List<String> relevant = new ArrayList<>();
            node.path("relevant").forEach(key -> relevant.add(key.asText()));
            queries.add(new Query(node.path("resume").asText(), node.path("query").asText(), relevant, node.path("hard").asBoolean(false)));
        }
        ResumeChunker chunker = new ResumeChunker();
        Map<String, CanonicalResume> resumes = new LinkedHashMap<>();
        resumeNodes.fields().forEachRemaining(entry -> resumes.put(entry.getKey(), canonicalize(chunker.structuredChunks(entry.getValue()))));
        for (Query query : queries) {
            CanonicalResume resume = resumes.get(query.resume());
            if (resume == null) {
                throw new IllegalStateException("Unknown resume in query: " + query.resume());
            }
            Set<String> keys = resume.spansByKey().keySet();
            if (!keys.containsAll(query.relevant())) {
                throw new IllegalStateException("Query labels a chunk that does not exist: " + query);
            }
        }
        return new RagEvaluator(resumes, queries);
    }

    List<Query> queries() {
        return queries;
    }

    int resumeCount() {
        return resumes.size();
    }

    static ChunkStrategy structured() {
        return new ChunkStrategy() {
            public String name() {
                return "structured";
            }

            public List<SpannedChunk> chunk(CanonicalResume resume) {
                return resume.structuredChunks();
            }
        };
    }

    static ChunkStrategy fixedSize(int size, int overlap) {
        return new ChunkStrategy() {
            public String name() {
                return "fixed-" + size + "/" + overlap;
            }

            public List<SpannedChunk> chunk(CanonicalResume resume) {
                List<SpannedChunk> chunks = new ArrayList<>();
                int cursor = 0;
                for (ResumeChunk chunk : ResumeChunker.fixedSize(resume.text(), size, overlap)) {
                    int start = resume.text().indexOf(chunk.content(), cursor);
                    chunks.add(new SpannedChunk(chunk.key(), chunk.content(), start, start + chunk.content().length()));
                    cursor = start + 1;
                }
                return chunks;
            }
        };
    }

    double averageChunksPerResume(ChunkStrategy strategy) {
        return resumes.values().stream().mapToInt(resume -> strategy.chunk(resume).size()).average().orElse(0);
    }

    Metrics evaluate(ChunkStrategy strategy, EmbeddingClient embeddings, int k) {
        Index index = new Index(strategy, embeddings);
        double recall = 0;
        double precision = 0;
        double reciprocalRank = 0;
        int count = 0;
        for (Query query : queries) {
            if (!query.positive()) continue;
            List<Scored> ranked = index.search(query);
            Map<String, SpannedChunk> labels = resumes.get(query.resume()).spansByKey();
            List<Scored> top = ranked.subList(0, Math.min(k, ranked.size()));

            Set<String> found = new HashSet<>();
            int relevantInTop = 0;
            for (Scored scored : top) {
                boolean relevant = false;
                for (String key : query.relevant()) {
                    if (overlaps(scored.chunk(), labels.get(key))) {
                        found.add(key);
                        relevant = true;
                    }
                }
                if (relevant) relevantInTop++;
            }
            recall += (double) found.size() / query.relevant().size();
            precision += (double) relevantInTop / top.size();
            for (int rank = 0; rank < ranked.size(); rank++) {
                if (isRelevant(ranked.get(rank).chunk(), query, labels)) {
                    reciprocalRank += 1.0 / (rank + 1);
                    break;
                }
            }
            count++;
        }
        return new Metrics(recall / count, precision / count, reciprocalRank / count, count);
    }

    FilteredMetrics evaluateWithThreshold(ChunkStrategy strategy, EmbeddingClient embeddings, int k, double threshold, boolean termCheck) {
        Index index = new Index(strategy, embeddings);
        double recall = 0;
        double precision = 0;
        double evidence = 0;
        int count = 0;
        int withEvidence = 0;
        for (Query query : queries) {
            if (!query.positive()) continue;
            Map<String, SpannedChunk> labels = resumes.get(query.resume()).spansByKey();
            // 与生产一致：先按阈值和技术检查过滤，再取前 k 个（评测简历的块数少于生产的候选池，等价于从全部候选里过滤）。
            List<Scored> kept = index.search(query).stream()
                    .filter(scored -> scored.similarity() >= threshold)
                    .filter(scored -> !termCheck || TechnologyTerms.supports(query.query(), scored.chunk().content()))
                    .limit(k)
                    .toList();
            Set<String> found = new HashSet<>();
            int relevant = 0;
            for (Scored scored : kept) {
                if (isRelevant(scored.chunk(), query, labels)) relevant++;
                query.relevant().stream().filter(key -> overlaps(scored.chunk(), labels.get(key))).forEach(found::add);
            }
            recall += (double) found.size() / query.relevant().size();
            if (!kept.isEmpty()) {
                precision += (double) relevant / kept.size();
                withEvidence++;
            }
            evidence += kept.size();
            count++;
        }
        return new FilteredMetrics(recall / count, withEvidence == 0 ? 0 : precision / withEvidence, evidence / count);
    }

    /**
     * 等字符预算比较：按排名依次取块，直到累计字符数达到预算（至少取一块）。
     * 不同切块策略的块大小差别很大，"相同 k"对大块不公平地有利——3 个 800 字的块几乎就是整份简历；
     * 真正的约束是能塞进 prompt 的上下文长度，所以按预算比较才公平。
     */
    BudgetMetrics evaluateWithBudget(ChunkStrategy strategy, EmbeddingClient embeddings, int budgetChars) {
        Index index = new Index(strategy, embeddings);
        double recall = 0;
        double contextPrecision = 0;
        double chars = 0;
        int count = 0;
        for (Query query : queries) {
            if (!query.positive()) continue;
            Map<String, SpannedChunk> labels = resumes.get(query.resume()).spansByKey();
            int used = 0;
            int relevantChars = 0;
            Set<String> found = new HashSet<>();
            for (Scored scored : index.search(query)) {
                SpannedChunk chunk = scored.chunk();
                int length = chunk.end() - chunk.start();
                if (used > 0 && used + length > budgetChars) break;
                used += length;
                int best = 0;
                for (String key : query.relevant()) {
                    SpannedChunk label = labels.get(key);
                    if (overlaps(chunk, label)) found.add(key);
                    best = Math.max(best, Math.max(0, Math.min(chunk.end(), label.end()) - Math.max(chunk.start(), label.start())));
                }
                relevantChars += best;
            }
            recall += (double) found.size() / query.relevant().size();
            contextPrecision += used == 0 ? 0 : (double) relevantChars / used;
            chars += used;
            count++;
        }
        return new BudgetMetrics(recall / count, contextPrecision / count, chars / count);
    }

    /**
     * 阈值扫描：第一个"超过阈值（termCheck 时还要通过 {@link TechnologyTerms#supports}）"的片段就是展示给用户的证据。
     * 正样本需要这个片段确实相关才算正确展示证据；负样本需要一个片段都不通过才算正确判为未覆盖。
     *
     * @param termCheck 是否模拟生产中的"点名的技术必须出现在证据里"检查
     */
    List<ThresholdResult> sweepThresholds(ChunkStrategy strategy, EmbeddingClient embeddings, double from, double to, double step,
                                          boolean termCheck) {
        Index index = new Index(strategy, embeddings);
        List<QueryOutcome> outcomes = queries.stream().map(query -> outcome(index, query, termCheck)).toList();
        List<ThresholdResult> results = new ArrayList<>();
        for (double threshold = from; threshold <= to + 1e-9; threshold += step) {
            double rounded = Math.round(threshold * 100) / 100.0;
            long positives = outcomes.stream().filter(o -> o.query().positive()).count();
            long negatives = outcomes.size() - positives;
            long correctPositives = outcomes.stream()
                    .filter(o -> o.query().positive() && o.hit() && o.topSimilarity() >= rounded).count();
            long correctNegatives = outcomes.stream()
                    .filter(o -> !o.query().positive() && o.topSimilarity() < rounded).count();
            long hardNegatives = outcomes.stream().filter(o -> o.query().hard()).count();
            long correctHardNegatives = outcomes.stream()
                    .filter(o -> o.query().hard() && o.topSimilarity() < rounded).count();
            results.add(new ThresholdResult(rounded, (double) correctPositives / positives, (double) correctNegatives / negatives,
                    hardNegatives == 0 ? 1 : (double) correctHardNegatives / hardNegatives));
        }
        return results;
    }

    /** 每条正样本查询的 Recall@k，用于找出某个参数改变了哪些查询的结果。 */
    Map<Query, Double> recallPerQuery(ChunkStrategy strategy, EmbeddingClient embeddings, int k) {
        Index index = new Index(strategy, embeddings);
        Map<Query, Double> result = new LinkedHashMap<>();
        for (Query query : queries) {
            if (!query.positive()) continue;
            Map<String, SpannedChunk> labels = resumes.get(query.resume()).spansByKey();
            List<Scored> ranked = index.search(query);
            Set<String> found = new HashSet<>();
            for (Scored scored : ranked.subList(0, Math.min(k, ranked.size()))) {
                query.relevant().stream().filter(key -> overlaps(scored.chunk(), labels.get(key))).forEach(found::add);
            }
            result.put(query, (double) found.size() / query.relevant().size());
        }
        return result;
    }

    List<QueryOutcome> outcomes(ChunkStrategy strategy, EmbeddingClient embeddings) {
        Index index = new Index(strategy, embeddings);
        return queries.stream().map(query -> outcome(index, query, false)).toList();
    }

    /** termCheck 时 top-1 指第一个通过技术检查的片段；一个都不通过时相似度记为 0（任何阈值下都是未覆盖）。 */
    private QueryOutcome outcome(Index index, Query query, boolean termCheck) {
        List<Scored> ranked = index.search(query).stream()
                .filter(scored -> !termCheck || TechnologyTerms.supports(query.query(), scored.chunk().content()))
                .toList();
        Map<String, SpannedChunk> labels = resumes.get(query.resume()).spansByKey();
        boolean hit = !ranked.isEmpty() && isRelevant(ranked.get(0).chunk(), query, labels);
        List<String> topKeys = ranked.stream().limit(3).map(scored -> scored.chunk().key()).toList();
        return new QueryOutcome(query, topKeys, ranked.isEmpty() ? 0 : ranked.get(0).similarity(), hit);
    }

    private static boolean isRelevant(SpannedChunk chunk, Query query, Map<String, SpannedChunk> labels) {
        return query.relevant().stream().anyMatch(key -> overlaps(chunk, labels.get(key)));
    }

    private static boolean overlaps(SpannedChunk chunk, SpannedChunk label) {
        int overlap = Math.min(chunk.end(), label.end()) - Math.max(chunk.start(), label.start());
        int shorter = Math.min(chunk.end() - chunk.start(), label.end() - label.start());
        return overlap > 0 && overlap * 2 >= shorter;
    }

    private static CanonicalResume canonicalize(List<ResumeChunk> chunks) {
        StringBuilder text = new StringBuilder();
        List<SpannedChunk> spans = new ArrayList<>();
        for (ResumeChunk chunk : chunks) {
            if (!text.isEmpty()) text.append("\n\n");
            int start = text.length();
            text.append(chunk.content());
            spans.add(new SpannedChunk(chunk.key(), chunk.content(), start, text.length()));
        }
        return new CanonicalResume(text.toString(), spans);
    }

    private static JsonNode read(String path) throws IOException {
        try (InputStream input = RagEvaluator.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("Missing evaluation resource " + path);
            return MAPPER.readTree(input);
        }
    }

    private record Scored(SpannedChunk chunk, double similarity) {
    }

    /** 每份简历的块向量只算一次，查询时做精确余弦检索（与生产中按版本过滤后的精确检索一致）。 */
    private final class Index {
        private final EmbeddingClient embeddings;
        private final Map<String, List<SpannedChunk>> chunks = new HashMap<>();
        private final Map<String, List<float[]>> vectors = new HashMap<>();

        Index(ChunkStrategy strategy, EmbeddingClient embeddings) {
            this.embeddings = embeddings;
            resumes.forEach((name, resume) -> {
                List<SpannedChunk> resumeChunks = strategy.chunk(resume);
                chunks.put(name, resumeChunks);
                vectors.put(name, embeddings.embed(resumeChunks.stream().map(SpannedChunk::content).toList()));
            });
        }

        List<Scored> search(Query query) {
            float[] queryVector = embeddings.embed(List.of(query.query())).get(0);
            List<SpannedChunk> resumeChunks = chunks.get(query.resume());
            List<float[]> resumeVectors = vectors.get(query.resume());
            List<Scored> scored = new ArrayList<>();
            for (int i = 0; i < resumeChunks.size(); i++) {
                scored.add(new Scored(resumeChunks.get(i), HashingEmbeddingClient.cosine(queryVector, resumeVectors.get(i))));
            }
            scored.sort(Comparator.comparingDouble(Scored::similarity).reversed());
            return scored;
        }
    }
}
