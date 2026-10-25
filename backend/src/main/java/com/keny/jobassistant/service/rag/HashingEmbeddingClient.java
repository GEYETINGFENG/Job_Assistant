package com.keny.jobassistant.service.rag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地确定性 embedding：把词和相邻词对哈希到固定维度（feature hashing），次线性词频，L2 归一化。
 *
 * 它本质是词法检索的向量化，没有语义理解（"Kubernetes" 和 "K8s" 不会相近），
 * 但确定、免费、离线，适合作为基线和本地开发；DashScope 可用时改配置切换为真正的语义 embedding。
 */
public class HashingEmbeddingClient implements EmbeddingClient {

    public static final String MODEL = "local-hash-v1";
    public static final int DIMENSIONS = 1024;

    // 保留 c++、c#、node.js、ci/cd 这类带符号的技术名。
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9][a-z0-9+#./-]*");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "with", "at", "by", "from", "as",
            "is", "are", "be", "was", "were", "will", "you", "we", "our", "your", "their", "this", "that",
            "it", "its", "into", "using", "use", "used", "have", "has", "had", "plus", "experience", "years");

    private final int dimensions;
    private final boolean stemming;

    /**
     * 默认不做词干化：评测中词干化让 application、model 这类泛用词匹配到更多段落，反而把真正相关的块挤出前 3
     * （28 条查询中有 2 条变差、没有变好的，见 docs/rag-evaluation.md 实验 3）。
     */
    public HashingEmbeddingClient() {
        this(DIMENSIONS, false);
    }

    public HashingEmbeddingClient(int dimensions, boolean stemming) {
        this.dimensions = dimensions;
        this.stemming = stemming;
    }

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embedOne(text));
        }
        return vectors;
    }

    float[] embedOne(String text) {
        List<String> tokens = tokenize(text);
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            counts.merge(tokens.get(i), 1, Integer::sum);
            if (i + 1 < tokens.size()) {
                counts.merge(tokens.get(i) + "_" + tokens.get(i + 1), 1, Integer::sum);
            }
        }
        float[] vector = new float[dimensions];
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            long hash = fnv1a64(entry.getKey());
            int index = (int) Long.remainderUnsigned(hash, dimensions);
            // 用另一位决定正负号，抵消哈希冲突带来的偏差。
            float sign = (hash >>> 63) == 0 ? 1f : -1f;
            vector[index] += sign * (float) (1 + Math.log(entry.getValue()));
        }
        return normalize(vector);
    }

    List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        Matcher matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String token = trimTrailingPunctuation(matcher.group());
            if (token.isEmpty() || STOP_WORDS.contains(token)) {
                continue;
            }
            tokens.add(stemming ? stem(token) : token);
        }
        return tokens;
    }

    /**
     * 轻量词干化，只处理纯字母的长词，不碰 c++、node.js 等技术名。
     * 复数和词形变化必须两边归一到同一个形式：databases / database -> database，
     * technologies -> technology，deployed / deploying -> deploy。
     * 早期版本把 "es" 一律去掉，databases 变成 databas、database 却不变，两者反而匹配不上（评测里召回下降了 10 个点）。
     */
    static String stem(String token) {
        if (token.length() <= 4 || !token.chars().allMatch(Character::isLetter)) {
            return token;
        }
        if (token.endsWith("ies")) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.endsWith("sses")) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("s") && !token.endsWith("ss") && !token.endsWith("us") && !token.endsWith("is")) {
            return token.substring(0, token.length() - 1);
        }
        for (String suffix : new String[]{"ing", "ed"}) {
            String base = token.substring(0, token.length() - suffix.length());
            if (token.endsWith(suffix) && base.length() >= 4 && base.chars().anyMatch(c -> "aeiou".indexOf(c) >= 0)) {
                return base;
            }
        }
        return token;
    }

    private static String trimTrailingPunctuation(String token) {
        int end = token.length();
        while (end > 0 && ".-/".indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        return token.substring(0, end);
    }

    private static float[] normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            return vector;
        }
        float inverse = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= inverse;
        }
        return vector;
    }

    private static long fnv1a64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /**
     * 两个已归一化向量的余弦相似度（点积）。
     */
    public static double cosine(float[] left, float[] right) {
        double dot = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
        }
        return dot;
    }
}
