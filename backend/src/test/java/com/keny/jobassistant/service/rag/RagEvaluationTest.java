package com.keny.jobassistant.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.service.LlmRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

/**
 * 检索质量回归测试 + 评测报告。
 * 平时只断言质量下限（结构化切块不能比固定长度差、Recall@3 不低于基线），防止改动悄悄让检索变差；
 * RAG_EVAL_REPORT=true 时把完整结果写入 docs/rag-evaluation.md；
 * 同时设置 RAG_EVAL_PROVIDER=dashscope（需要 DASHSCOPE_API_KEY）时，报告额外包含真实语义 embedding 与哈希基线的对比。
 */
class RagEvaluationTest {

    private static final Path REPORT = Path.of("..", "docs", "rag-evaluation.md");
    /** 生产默认配置：不做词干化。 */
    private static final EmbeddingClient HASH = new HashingEmbeddingClient();
    private static final EmbeddingClient HASH_STEMMING = new HashingEmbeddingClient(HashingEmbeddingClient.DIMENSIONS, true);

    private static final String DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private static RagEvaluator evaluator;
    /** 只有 RAG_EVAL_PROVIDER=dashscope 时才有值：平时的单元测试不联网、不花额度。 */
    private static CachingEmbeddingClient semantic;

    @BeforeAll
    static void load() throws IOException {
        evaluator = RagEvaluator.loadDefaultDataset();
        semantic = semanticFromEnvironment();
    }

    static CachingEmbeddingClient semanticFromEnvironment() {
        if (!"dashscope".equalsIgnoreCase(System.getenv("RAG_EVAL_PROVIDER"))) {
            return null;
        }
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("RAG_EVAL_PROVIDER=dashscope requires DASHSCOPE_API_KEY");
        }
        String baseUrl = Optional.ofNullable(System.getenv("BAILIAN_BASE_URL")).filter(url -> !url.isBlank()).orElse(DEFAULT_BASE_URL);
        String model = Optional.ofNullable(System.getenv("RAG_EMBEDDING_MODEL")).filter(name -> !name.isBlank()).orElse("text-embedding-v4");
        RestClient restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        // 评测是一次性的离线任务，几十次请求，不经过 Redis 限流。
        LlmRateLimiter rateLimiter = mock(LlmRateLimiter.class);
        return new CachingEmbeddingClient(new DashScopeEmbeddingClient(restClient, new ObjectMapper(), rateLimiter,
                new SimpleMeterRegistry(), model, HashingEmbeddingClient.DIMENSIONS));
    }

    @Test
    void datasetShouldHaveEnoughLabelledQueries() {
        long positives = evaluator.queries().stream().filter(RagEvaluator.Query::positive).count();
        long negatives = evaluator.queries().size() - positives;

        assertThat(positives).isGreaterThanOrEqualTo(24);
        assertThat(negatives).isGreaterThanOrEqualTo(5);
        assertThat(evaluator.queries().stream().filter(RagEvaluator.Query::hard).count()).isGreaterThanOrEqualTo(12);
    }

    /**
     * 在相同的上下文预算下，结构化切块的召回不能比固定长度差。
     * 上下文精度不做断言：结构化块保留完整段落，会比 300 字的小块多带一些上下文，这是有意的取舍（报告里如实列出）。
     */
    @Test
    void structuredChunkingShouldNotRetrieveWorseThanFixedSizeAtSameBudget() {
        var structured = evaluator.evaluateWithBudget(RagEvaluator.structured(), HASH, CONTEXT_BUDGET);

        for (var baseline : List.of(RagEvaluator.fixedSize(300, 50), RagEvaluator.fixedSize(800, 150))) {
            var other = evaluator.evaluateWithBudget(baseline, HASH, CONTEXT_BUDGET);
            assertThat(structured.recall()).as("recall vs %s", baseline.name()).isGreaterThanOrEqualTo(other.recall());
        }
    }

    /** 质量下限：当前哈希 embedding 的基线成绩，低于它说明改动让检索变差了。 */
    @Test
    void structuredRecallAtThreeShouldStayAboveBaseline() {
        var metrics = evaluator.evaluate(RagEvaluator.structured(), HASH, 3);

        assertThat(metrics.recall()).isGreaterThanOrEqualTo(RECALL_AT_3_FLOOR);
        assertThat(metrics.mrr()).isGreaterThanOrEqualTo(MRR_FLOOR);
    }

    /** 代码里的校准阈值必须是评测集上 Youden J 最高的阈值之一：数据集或 embedding 改了，阈值要跟着重新校准。 */
    @Test
    void calibratedThresholdShouldBeBestOnEvaluationSet() {
        assertCalibratedThresholdIsBest(HASH, 0.5);
    }

    /** 只在 RAG_EVAL_PROVIDER=dashscope 时运行：语义 embedding 不能比词法基线差，阈值也要是最优的。 */
    @Test
    void semanticEmbeddingShouldBeatLexicalBaseline() {
        assumeTrue(semantic != null, "set RAG_EVAL_PROVIDER=dashscope to evaluate the semantic embedding");

        var lexical = evaluator.evaluate(RagEvaluator.structured(), HASH, 3);
        var dense = evaluator.evaluate(RagEvaluator.structured(), semantic, 3);
        assertThat(dense.recall()).isGreaterThanOrEqualTo(lexical.recall());
        assertThat(dense.mrr()).isGreaterThanOrEqualTo(lexical.mrr());
        assertCalibratedThresholdIsBest(semantic, 0.9);
    }

    private static void assertCalibratedThresholdIsBest(EmbeddingClient model, double maxThreshold) {
        var sweep = evaluator.sweepThresholds(RagEvaluator.structured(), model, 0.0, maxThreshold, 0.05, true);
        double bestYouden = sweep.stream().mapToDouble(RagEvaluator.ThresholdResult::youden).max().orElseThrow();
        double calibrated = ResumeRetriever.CALIBRATIONS.get(model.model()).similarityThreshold();
        var atCalibrated = sweep.stream().filter(result -> Math.abs(result.threshold() - calibrated) < 1e-9).findFirst().orElseThrow();
        assertThat(atCalibrated.youden()).as("Youden J at %s for %s", calibrated, model.model()).isCloseTo(bestYouden, within(1e-9));
    }

    /** 实测基线（local-hash-v1、结构化切块）：Recall@3 = 89.3%，MRR = 0.72；下限留少量余量。 */
    static final double RECALL_AT_3_FLOOR = 0.85;
    static final double MRR_FLOOR = 0.65;
    /** 每条要求放进 prompt 的证据上限，约等于 3 个结构化块。 */
    static final int CONTEXT_BUDGET = 800;

    @Test
    void writeReportWhenRequested() throws IOException {
        if (!"true".equalsIgnoreCase(System.getenv("RAG_EVAL_REPORT"))) {
            return;
        }
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, report(), StandardCharsets.UTF_8);
    }

    static String report() {
        StringBuilder md = new StringBuilder();
        long positives = evaluator.queries().stream().filter(RagEvaluator.Query::positive).count();
        long hard = evaluator.queries().stream().filter(RagEvaluator.Query::hard).count();
        md.append("# RAG Retrieval Evaluation\n\n")
                .append("Generated by `RagEvaluationTest` (`RAG_EVAL_REPORT=true`). Dataset: ")
                .append(evaluator.resumeCount()).append(" fictional resumes, ")
                .append(positives).append(" labelled queries with at least one relevant section, ")
                .append(evaluator.queries().size() - positives).append(" queries with no relevant section, of which ")
                .append(hard).append(" are hard negatives (same field as the resume, but naming a technology the resume never mentions) ")
                .append("(`backend/src/test/resources/rag-eval/`). Retrieval is exact cosine search within one resume, ")
                .append("the same scope as production. Embedding: `").append(HASH.model()).append("` (local feature hashing, lexical).\n\n");

        var strategies = List.of(RagEvaluator.structured(), RagEvaluator.fixedSize(300, 50), RagEvaluator.fixedSize(800, 150));
        md.append("## Experiment 1: chunking strategy\n\n")
                .append("### 1a. Same context budget (").append(CONTEXT_BUDGET).append(" characters per requirement)\n\n")
                .append("Chunks are taken in rank order until the budget is used (at least one chunk). This is the fair comparison: ")
                .append("the real constraint is how much evidence fits in the prompt.\n\n")
                .append("| Strategy | Chunks / resume | Recall | Context precision | Chars retrieved / query |\n|---|---|---|---|---|\n");
        for (var strategy : strategies) {
            var m = evaluator.evaluateWithBudget(strategy, HASH, CONTEXT_BUDGET);
            md.append(row(strategy.name(), fmt1(evaluator.averageChunksPerResume(strategy)), pct(m.recall()),
                    pct(m.contextPrecision()), fmt1(m.charsPerQuery())));
        }
        md.append("\n### 1b. Same k (k = 3) — misleading on its own\n\n")
                .append("With only ~2 chunks per resume, fixed-800 retrieves almost the whole resume at k = 3, so its recall is inflated.\n\n")
                .append("| Strategy | Recall@3 | Precision@3 | MRR |\n|---|---|---|---|\n");
        for (var strategy : strategies) {
            var m = evaluator.evaluate(strategy, HASH, 3);
            md.append(row(strategy.name(), pct(m.recall()), pct(m.precision()), fmt(m.mrr())));
        }

        md.append("\n## Experiment 2: top-k (structured chunking)\n\n")
                .append("| k | Recall@k | Precision@k |\n|---|---|---|\n");
        for (int k : new int[]{1, 2, 3, 5}) {
            var m = evaluator.evaluate(RagEvaluator.structured(), HASH, k);
            md.append(row(Integer.toString(k), pct(m.recall()), pct(m.precision())));
        }

        md.append("\n## Experiment 3: stemming in the hashing embedding (structured, k = 3)\n\n")
                .append("| Stemming | Recall@3 | Precision@3 | MRR |\n|---|---|---|---|\n");
        for (var entry : List.of(new Object[]{"off (default)", HASH}, new Object[]{"on", HASH_STEMMING})) {
            var m = evaluator.evaluate(RagEvaluator.structured(), (EmbeddingClient) entry[1], 3);
            md.append(row((String) entry[0], pct(m.recall()), pct(m.precision()), fmt(m.mrr())));
        }

        var withStemming = evaluator.recallPerQuery(RagEvaluator.structured(), HASH_STEMMING, 3);
        var withoutStemming = evaluator.recallPerQuery(RagEvaluator.structured(), HASH, 3);
        md.append("\nQueries whose Recall@3 changes when stemming is turned on:\n\n")
                .append("| Resume | Query | Recall@3 off | Recall@3 on |\n|---|---|---|---|\n");
        withStemming.forEach((query, on) -> {
            double off = withoutStemming.get(query);
            if (off != on) md.append(row(query.resume(), query.query(), pct(off), pct(on)));
        });

        md.append("\n## Experiment 4: similarity threshold (structured, top-1)\n\n")
                .append("A requirement is shown as covered only when the best chunk's similarity reaches the threshold. ")
                .append("Positive = the top chunk is relevant and above the threshold; negative = a query with no relevant section is correctly rejected. ")
                .append("As in production, a chunk only counts as evidence if it mentions at least one technology the requirement names ")
                .append("(`TechnologyTerms.supports`).\n\n");
        thresholdTable(md, evaluator.sweepThresholds(RagEvaluator.structured(), HASH, 0.0, 0.5, 0.05, true));

        md.append("\n## Per-query results (structured, top-3)\n\n")
                .append("| Resume | Query | Labelled | Top-3 retrieved | Top-1 similarity | Top-1 relevant |\n|---|---|---|---|---|---|\n");
        for (var outcome : evaluator.outcomes(RagEvaluator.structured(), HASH)) {
            md.append(row(outcome.query().resume(), outcome.query().query(),
                    outcome.query().relevant().isEmpty() ? "(none)" : String.join(", ", outcome.query().relevant()),
                    String.join(", ", outcome.topKeys()), fmt(outcome.topSimilarity()),
                    outcome.query().positive() ? (outcome.hit() ? "yes" : "**no**") : outcome.query().hard() ? "n/a (hard)" : "n/a"));
        }

        if (semantic != null) {
            semanticComparison(md);
        } else {
            md.append("\n_Run with `RAG_EVAL_PROVIDER=dashscope` to add the comparison with a semantic embedding model._\n");
        }
        return md.toString();
    }

    /** 实验 5：同一数据集、同一检索方式，只换 embedding 模型。 */
    private static void semanticComparison(StringBuilder md) {
        var models = List.<EmbeddingClient>of(HASH, semantic);
        md.append("\n## Experiment 5: embedding model (structured chunking)\n\n")
                .append("Same dataset and the same exact cosine search; only the embedding model changes. `")
                .append(semantic.model()).append("` is called through the DashScope OpenAI-compatible API.\n\n")
                .append("### 5a. Retrieval quality\n\n")
                .append("| Embedding | Recall@1 | Recall@3 | Recall@5 | Precision@3 | MRR | Recall @ ").append(CONTEXT_BUDGET).append(" chars |\n")
                .append("|---|---|---|---|---|---|---|\n");
        for (EmbeddingClient model : models) {
            var at3 = evaluator.evaluate(RagEvaluator.structured(), model, 3);
            md.append(row("`" + model.model() + "`",
                    pct(evaluator.evaluate(RagEvaluator.structured(), model, 1).recall()),
                    pct(at3.recall()),
                    pct(evaluator.evaluate(RagEvaluator.structured(), model, 5).recall()),
                    pct(at3.precision()), fmt(at3.mrr()),
                    pct(evaluator.evaluateWithBudget(RagEvaluator.structured(), model, CONTEXT_BUDGET).recall())));
        }

        md.append("\n### 5b. Similarity distribution of the top-1 chunk\n\n")
                .append("The threshold has to separate \"top chunk is relevant\" from \"the resume has nothing relevant\". ")
                .append("Where these ranges overlap, no threshold can be perfect.\n\n")
                .append("| Embedding | Positives, top-1 relevant (min / mean / max) | Unrelated negatives | Hard negatives |\n|---|---|---|---|\n");
        for (EmbeddingClient model : models) {
            var outcomes = evaluator.outcomes(RagEvaluator.structured(), model);
            md.append(row("`" + model.model() + "`",
                    range(outcomes.stream().filter(o -> o.query().positive() && o.hit()).mapToDouble(RagEvaluator.QueryOutcome::topSimilarity).toArray()),
                    range(outcomes.stream().filter(o -> !o.query().positive() && !o.query().hard()).mapToDouble(RagEvaluator.QueryOutcome::topSimilarity).toArray()),
                    range(outcomes.stream().filter(o -> o.query().hard()).mapToDouble(RagEvaluator.QueryOutcome::topSimilarity).toArray())));
        }

        md.append("\n### 5c. Similarity threshold for `").append(semantic.model()).append("`, threshold only\n\n")
                .append("Semantic similarities sit much higher than lexical ones, so the hashing threshold cannot be reused. ")
                .append("Hard negatives overlap with real evidence, so no threshold alone rejects them without losing positives.\n\n");
        thresholdTable(md, evaluator.sweepThresholds(RagEvaluator.structured(), semantic, 0.0, 0.9, 0.05, false));

        md.append("\n### 5d. Similarity threshold for `").append(semantic.model()).append("` with the named-technology check (production)\n\n")
                .append("A chunk only counts as evidence if it mentions at least one technology the requirement names. ")
                .append("The threshold then only has to separate related topics from unrelated ones.\n\n");
        thresholdTable(md, evaluator.sweepThresholds(RagEvaluator.structured(), semantic, 0.0, 0.9, 0.05, true));

        md.append("\n### 5e. Top-k after the threshold and the technology check (`").append(semantic.model()).append("`, threshold ")
                .append(fmt(ResumeRetriever.CALIBRATIONS.get(semantic.model()).similarityThreshold())).append(")\n\n")
                .append("Production takes the top k chunks and then drops those below the threshold, so k is only an upper bound ")
                .append("on how much evidence reaches the prompt.\n\n")
                .append("| k | Recall | Precision of kept chunks | Chunks kept / requirement |\n|---|---|---|---|\n");
        double threshold = ResumeRetriever.CALIBRATIONS.get(semantic.model()).similarityThreshold();
        for (int k : new int[]{1, 2, 3, 5}) {
            var m = evaluator.evaluateWithThreshold(RagEvaluator.structured(), semantic, k, threshold, true);
            md.append(row(Integer.toString(k), pct(m.recall()), pct(m.precision()), fmt1(m.evidencePerQuery())));
        }

        var lexical = evaluator.outcomes(RagEvaluator.structured(), HASH);
        var dense = evaluator.outcomes(RagEvaluator.structured(), semantic);
        md.append("\n### 5f. Queries where the top-1 result changes\n\n")
                .append("| Resume | Query | Labelled | `").append(HASH.model()).append("` top-1 | `").append(semantic.model()).append("` top-1 |\n")
                .append("|---|---|---|---|---|\n");
        for (int i = 0; i < lexical.size(); i++) {
            var before = lexical.get(i);
            var after = dense.get(i);
            if (before.topKeys().get(0).equals(after.topKeys().get(0))) continue;
            var query = before.query();
            md.append(row(query.resume(), query.query(),
                    query.relevant().isEmpty() ? "(none)" : String.join(", ", query.relevant()),
                    topOne(before), topOne(after)));
        }
        md.append("\n_").append(semantic.calls()).append(" embedding requests in this run (vectors cached per text)._\n");
    }

    private static void thresholdTable(StringBuilder md, List<RagEvaluator.ThresholdResult> sweep) {
        var best = sweep.stream().max(Comparator.comparingDouble(RagEvaluator.ThresholdResult::youden)).orElseThrow();
        md.append("| Threshold | Positives with correct evidence | Negatives rejected | Hard negatives rejected | Youden J |\n|---|---|---|---|---|\n");
        for (var result : sweep) {
            String label = result.threshold() == best.threshold() ? "**" + fmt(result.threshold()) + "**" : fmt(result.threshold());
            md.append(row(label, pct(result.positiveRate()), pct(result.negativeRate()), pct(result.hardNegativeRate()), fmt(result.youden())));
        }
        md.append("\nBest threshold by Youden J: **").append(fmt(best.threshold())).append("**.\n");
    }

    private static String topOne(RagEvaluator.QueryOutcome outcome) {
        String verdict = !outcome.query().positive() ? "" : outcome.hit() ? " ✓" : " ✗";
        return outcome.topKeys().get(0) + " (" + fmt(outcome.topSimilarity()) + ")" + verdict;
    }

    private static String range(double[] values) {
        if (values.length == 0) return "–";
        double min = Arrays.stream(values).min().orElse(0);
        double max = Arrays.stream(values).max().orElse(0);
        double mean = Arrays.stream(values).average().orElse(0);
        return fmt(min) + " / " + fmt(mean) + " / " + fmt(max);
    }

    private static String row(String... cells) {
        return "| " + String.join(" | ", cells) + " |\n";
    }

    private static String pct(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String fmt1(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
