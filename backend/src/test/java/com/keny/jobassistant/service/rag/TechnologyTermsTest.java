package com.keny.jobassistant.service.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TechnologyTermsTest {

    private static final String SKILLS = "Skills: Python, C++, SQL, PyTorch, scikit-learn, MLflow, PostgreSQL";

    /** 本机真实匹配里被误判为已覆盖的两条要求。 */
    @Test
    void namedTechnologyMissingFromChunkShouldNotBeEvidence() {
        assertThat(TechnologyTerms.supports("Experience deploying models with Docker and Kubernetes on AWS", SKILLS)).isFalse();
        assertThat(TechnologyTerms.supports("Familiarity with Spark for large-scale data processing", SKILLS)).isFalse();
    }

    @Test
    void anyNamedTechnologyShouldBeEnough() {
        assertThat(TechnologyTerms.supports("Experience with Kafka or RabbitMQ", "Built an event pipeline on Kafka")).isTrue();
        assertThat(TechnologyTerms.supports("Hands-on experience with PyTorch and scikit-learn", SKILLS)).isTrue();
    }

    /** 概念词不要求字面出现：简历常用别的说法。 */
    @Test
    void conceptsShouldBeLeftToSemanticSimilarity() {
        assertThat(TechnologyTerms.named("Break a monolithic application into microservices")).isEmpty();
        assertThat(TechnologyTerms.supports("Break a monolithic application into microservices",
                "Split the legacy monolith into twelve Spring Boot services")).isTrue();
    }

    @Test
    void aliasesAndImpliedTechnologiesShouldCount() {
        assertThat(TechnologyTerms.supports("Run workloads on K8s", "Certified Kubernetes Administrator")).isTrue();
        assertThat(TechnologyTerms.supports("Optimize slow SQL queries", "Tuned PostgreSQL queries with composite indexes")).isTrue();
        // 反过来不成立：会 SQL 不代表会 PostgreSQL。
        assertThat(TechnologyTerms.supports("Experience with PostgreSQL", "Wrote SQL reports")).isFalse();
    }

    /** 按单词边界匹配：Java 不能匹配 JavaScript，SQL 不能匹配 PostgreSQL 里的子串。 */
    @Test
    void termsShouldMatchWholeWordsOnly() {
        assertThat(TechnologyTerms.supports("Strong Java skills", "Built UIs in JavaScript")).isFalse();
        assertThat(TechnologyTerms.named("Experience with PostgreSQL")).containsExactly("postgresql");
    }

    /** 最长匹配：点名 React Native 时，只提到 React 的片段不算证据。 */
    @Test
    void longerTechnologyNameShouldWinOverTheShorterOneInside() {
        assertThat(TechnologyTerms.named("Experience building mobile apps with React Native")).containsExactly("react native");
        assertThat(TechnologyTerms.supports("Experience building mobile apps with React Native", "Skills: TypeScript, React, Vue")).isFalse();
        assertThat(TechnologyTerms.named("Strong C++ skills")).containsExactly("c++");
    }

    @Test
    void requirementWithoutNamedTechnologyShouldAcceptAnyChunk() {
        assertThat(TechnologyTerms.supports("Graduate degree in statistics or a related field", "Education: M.S. in Statistics")).isTrue();
    }
}
