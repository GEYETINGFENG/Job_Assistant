package com.keny.jobassistant.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HashingEmbeddingClientTest {

    private final HashingEmbeddingClient client = new HashingEmbeddingClient();

    @Test
    void embeddingsShouldBeDeterministicNormalizedAndFixedSize() {
        float[] first = client.embed(List.of("Built Kafka pipelines in Java")).get(0);
        float[] second = client.embed(List.of("Built Kafka pipelines in Java")).get(0);

        assertThat(first).hasSize(HashingEmbeddingClient.DIMENSIONS).containsExactly(second);
        assertThat(HashingEmbeddingClient.cosine(first, first)).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void relatedTextShouldBeCloserThanUnrelatedText() {
        List<float[]> vectors = client.embed(List.of(
                "Experience deploying services with Kubernetes and Docker",
                "Deployed microservices to Kubernetes clusters using Docker images",
                "Graduated with a degree in English literature"));

        double related = HashingEmbeddingClient.cosine(vectors.get(0), vectors.get(1));
        double unrelated = HashingEmbeddingClient.cosine(vectors.get(0), vectors.get(2));
        // 词法 embedding 的绝对分数偏低（见评测报告的阈值实验），这里只要求相关文本明显更近。
        assertThat(related).isGreaterThan(0.1).isGreaterThan(unrelated + 0.1);
    }

    @Test
    void tokenizerShouldKeepTechnicalNamesAndDropStopWords() {
        assertThat(client.tokenize("Experience with C++, C#, Node.js and CI/CD."))
                .containsExactly("c++", "c#", "node.js", "ci/cd");
    }

    @Test
    void stemmingShouldNormalizeWordFormsConsistently() {
        // 单复数必须归一到同一个形式，否则反而互相匹配不上。
        assertThat(HashingEmbeddingClient.stem("databases")).isEqualTo(HashingEmbeddingClient.stem("database"));
        assertThat(HashingEmbeddingClient.stem("services")).isEqualTo(HashingEmbeddingClient.stem("service"));
        assertThat(HashingEmbeddingClient.stem("technologies")).isEqualTo("technology");
        assertThat(HashingEmbeddingClient.stem("deploying")).isEqualTo("deploy");
        assertThat(HashingEmbeddingClient.stem("deployed")).isEqualTo("deploy");
        assertThat(HashingEmbeddingClient.stem("business")).isEqualTo("business");
        assertThat(HashingEmbeddingClient.stem("status")).isEqualTo("status");
    }

    @Test
    void stemmingShouldNotTouchTechnicalNamesOrShortWords() {
        assertThat(HashingEmbeddingClient.stem("node.js")).isEqualTo("node.js");
        assertThat(HashingEmbeddingClient.stem("aws")).isEqualTo("aws");
        assertThat(HashingEmbeddingClient.stem("redis")).isEqualTo("redis");
    }

    @Test
    void emptyTextShouldProduceZeroVector() {
        float[] vector = client.embed(List.of("the and of")).get(0);

        assertThat(HashingEmbeddingClient.cosine(vector, vector)).isZero();
    }
}
