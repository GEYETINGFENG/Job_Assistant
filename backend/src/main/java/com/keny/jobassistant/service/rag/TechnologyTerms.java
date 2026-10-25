package com.keny.jobassistant.service.rag;

import com.keny.jobassistant.service.match.ResumeText;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JD 中常见的具体技术名，以及"点名的技术必须出现在证据里"的检查。
 *
 * 语义向量衡量的是话题相近，不是"简历真的会这个"：ML 简历对 "Hadoop and Hive" 的相似度和真正相关的片段差不多高
 * （docs/rag-evaluation.md 实验 5b 的难负样本）。所以要求里点名了具体技术时，证据片段必须字面提到其中至少一个。
 * "Microservices"、"Machine Learning" 这类概念词不做字面检查：简历常用别的说法表达同一件事
 * （"把单体拆成 12 个服务"），交给语义相似度判断。
 */
public final class TechnologyTerms {

    public static final List<String> VOCABULARY = List.of(
            "Java", "Kotlin", "Scala", "Python", "Go", "Golang", "Rust", "C", "C++", "C#", ".NET", "Swift",
            "JavaScript", "TypeScript", "Node.js", "React", "React Native", "Vue", "Angular", "RxJS", "Next.js", "HTML", "CSS",
            "Spring", "Spring Boot", "Spring Cloud", "Hibernate", "JPA", "MyBatis", "Django", "Flask", "FastAPI",
            "SQL", "PostgreSQL", "MySQL", "Oracle", "MongoDB", "Redis", "Elasticsearch", "Cassandra", "DynamoDB",
            "Snowflake", "ClickHouse",
            "Kafka", "RabbitMQ", "SQS", "gRPC", "GraphQL", "Apollo", "REST",
            "Docker", "Kubernetes", "Terraform", "Ansible", "AWS", "GCP", "Azure", "Linux", "Git", "CI/CD", "Jenkins",
            "Prometheus", "Grafana", "Datadog", "New Relic",
            "Microservices", "Distributed Systems", "System Design",
            "Machine Learning", "PyTorch", "TensorFlow", "JAX", "scikit-learn", "XGBoost", "MLflow", "Kubeflow", "SageMaker",
            "LLM", "RAG", "Spark", "Hadoop", "Hive", "Flink", "Airflow", "dbt"
    );

    /** 概念词：语义上可以有很多种说法，不要求字面出现。 */
    private static final Set<String> CONCEPTS = Set.of(
            "microservices", "distributed systems", "system design", "machine learning", "llm", "rag", "rest", "ci/cd");

    /** 同一种技术的不同写法：要求写其中一个，证据里出现任何一个都算。 */
    private static final List<Set<String>> ALIASES = List.of(
            Set.of("go", "golang"),
            Set.of("kubernetes", "k8s"),
            Set.of("postgresql", "postgres"),
            Set.of("gcp", "google cloud"),
            Set.of("aws", "amazon web services"));

    /**
     * 单向蕴含：会具体的数据库就算会 SQL（"Tuned PostgreSQL queries" 能证明 "SQL queries"），反过来不成立。
     * containsTerm 按单词边界匹配，"postgresql" 里的 "sql" 不会被当成 SQL。
     */
    private static final Map<String, Set<String>> IMPLIED_BY = Map.of(
            "sql", Set.of("postgresql", "postgres", "mysql", "sqlite", "sql server", "oracle"));

    private TechnologyTerms() {
    }

    /**
     * 要求里点名的具体技术（小写，已排除概念词）。按最长匹配：点名 "React Native" 时不再单独算 "React"，
     * 否则只会 React 的简历也能通过"其一即可"的检查。
     */
    public static List<String> named(String requirement) {
        String text = requirement.toLowerCase(Locale.ROOT);
        List<String> named = new ArrayList<>();
        for (String term : VOCABULARY) {
            String lower = term.toLowerCase(Locale.ROOT);
            if (!CONCEPTS.contains(lower) && ResumeText.containsTerm(text, lower)) {
                named.add(lower);
            }
        }
        for (Set<String> group : ALIASES) {
            for (String alias : group) {
                if (!named.contains(alias) && ResumeText.containsTerm(text, alias)) {
                    named.add(alias);
                }
            }
        }
        named.removeIf(term -> named.stream().anyMatch(other -> !other.equals(term) && ResumeText.containsTerm(other, term)));
        return named;
    }

    /**
     * 片段能否作为这条要求的证据：要求没点名具体技术时总是可以；
     * 点名了时，片段必须提到其中至少一个（"Kafka or RabbitMQ" 会其一即可，所以不要求全部出现）。
     */
    public static boolean supports(String requirement, String chunk) {
        List<String> named = named(requirement);
        if (named.isEmpty()) {
            return true;
        }
        String text = chunk.toLowerCase(Locale.ROOT);
        return named.stream().anyMatch(term -> evidenceTerms(term).stream().anyMatch(word -> ResumeText.containsTerm(text, word)));
    }

    /** 片段里出现哪些词就能证明会这项技术：本身、别名、以及蕴含它的更具体的技术。 */
    private static Set<String> evidenceTerms(String term) {
        Set<String> words = new HashSet<>(Set.of(term));
        ALIASES.stream().filter(group -> group.contains(term)).forEach(words::addAll);
        words.addAll(IMPLIED_BY.getOrDefault(term, Set.of()));
        return words;
    }
}
