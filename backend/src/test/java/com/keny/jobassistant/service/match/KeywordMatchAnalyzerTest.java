package com.keny.jobassistant.service.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.model.ai.EvidenceSnippet;
import com.keny.jobassistant.model.ai.RequirementEvidence;
import com.keny.jobassistant.model.enums.MatchEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordMatchAnalyzerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final KeywordMatchAnalyzer analyzer = new KeywordMatchAnalyzer();

    private static JsonNode resume() throws Exception {
        return MAPPER.readTree("""
                {"skills": ["Java", "Spring Boot", "PostgreSQL", "C++"],
                 "projects": [{"name": "Job Assistant", "technologies": ["Redis", "Docker"]}],
                 "experience": [{"company": "Example", "description": "Built REST APIs"}]}
                """);
    }

    @Test
    void shouldReportMatchedAndMissingSkills() throws Exception {
        var result = analyzer.analyze(resume(), "Backend Engineer",
                "We need Java and Spring Boot experience, plus Kafka and Kubernetes. Redis is a plus.", List.of());

        assertThat(result.analysis().matchedSkills()).containsExactly("Java", "Spring Boot", "Redis");
        assertThat(result.analysis().missingSkills()).containsExactly("Kafka", "Kubernetes");
        // 3 个命中，2 个缺失。
        assertThat(result.score()).isEqualTo(60);
        assertThat(result.promptTokens()).isNull();
        assertThat(analyzer.engine()).isEqualTo(MatchEngine.KEYWORD);
    }

    /** "Java" 不能命中 "JavaScript"，"C" 不能命中 "C++" 里的 C，"Go" 不能命中 "Google"。 */
    @Test
    void shouldMatchOnWordBoundariesIncludingSymbols() throws Exception {
        var result = analyzer.analyze(resume(), "Frontend Engineer", "JavaScript, C++ and Go, working at Google.", List.of());

        assertThat(result.analysis().matchedSkills()).containsExactly("C++");
        assertThat(result.analysis().missingSkills()).contains("JavaScript", "Go").doesNotContain("Java", "C");
    }

    @Test
    void shouldBeCaseInsensitive() throws Exception {
        var result = analyzer.analyze(resume(), null, "JAVA, postgresql", List.of());

        assertThat(result.analysis().matchedSkills()).containsExactly("Java", "PostgreSQL");
    }

    @Test
    void jobWithoutTechnicalKeywordsShouldScoreZeroWithExplanation() throws Exception {
        var result = analyzer.analyze(resume(), "Office Manager", "Great communication and organization skills.", List.of());

        assertThat(result.score()).isZero();
        assertThat(result.analysis().summary()).contains("no technical keywords");
    }

    @Test
    void retrievedEvidenceShouldBeReturnedAsRequirements() throws Exception {
        List<RequirementEvidence> evidence = List.of(new RequirementEvidence("Java experience", true,
                List.of(new EvidenceSnippet("SKILLS", "Skills: Java", 0.5))));

        var result = analyzer.analyze(resume(), "Backend", "Java", evidence);

        assertThat(result.analysis().requirements()).isEqualTo(evidence);
    }

    @Test
    void sameInputShouldAlwaysProduceSameResult() throws Exception {
        var first = analyzer.analyze(resume(), "Backend", "Java, Kafka", List.of());
        var second = analyzer.analyze(resume(), "Backend", "Java, Kafka", List.of());

        assertThat(second).isEqualTo(first);
    }
}
