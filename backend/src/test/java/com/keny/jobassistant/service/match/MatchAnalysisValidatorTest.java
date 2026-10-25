package com.keny.jobassistant.service.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keny.jobassistant.exception.MatchAnalysisException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** LLM 输出的业务校验：合法 JSON 不等于结构对，结构对不等于内容合理。 */
class MatchAnalysisValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String value) throws Exception {
        return MAPPER.readTree(value);
    }

    private static String valid(String score) {
        return """
                {"score": %s, "summary": "Strong backend match.",
                 "matchedSkills": ["Java", "java", "Spring Boot"], "missingSkills": ["Kafka"],
                 "gaps": ["Asks for 5 years, resume shows 3"], "suggestions": ["Quantify the API latency work"]}
                """.formatted(score);
    }

    @Test
    void validOutputShouldBeAcceptedAndSkillsDeduplicated() throws Exception {
        var result = MatchAnalysisValidator.validate(json(valid("82")));

        assertThat(result.score()).isEqualTo(82);
        assertThat(result.analysis().matchedSkills()).containsExactly("Java", "Spring Boot");
        assertThat(result.analysis().missingSkills()).containsExactly("Kafka");
        assertThat(result.analysis().gaps()).hasSize(1);
    }

    @Test
    void integralDoubleScoreShouldBeAccepted() throws Exception {
        assertThat(MatchAnalysisValidator.validate(json(valid("75.0"))).score()).isEqualTo(75);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "101", "82.5", "\"82\"", "null"})
    void invalidScoreShouldBeRejected(String score) {
        assertRetryableInvalid(valid(score));
    }

    /** 合法 JSON、反序列化也不会报错，但关键字段全没了：必须当作失败，而不是"成功但漏了一半"。 */
    @Test
    void outputMissingRequiredFieldsShouldBeRejected() {
        assertRetryableInvalid("{\"score\": 80, \"summary\": \"ok\"}");
    }

    @Test
    void nonStringArrayItemsShouldBeRejected() {
        assertRetryableInvalid("""
                {"score": 80, "summary": "ok", "matchedSkills": [{"name": "Java"}],
                 "missingSkills": [], "gaps": [], "suggestions": []}
                """);
    }

    /** 注入成功时常见的症状：超长、超量的输出。上限把能塞进结果的内容限制住。 */
    @Test
    void oversizedOutputShouldBeRejected() {
        String hugeSummary = "x".repeat(MatchAnalysisValidator.MAX_SUMMARY_LENGTH + 1);
        assertRetryableInvalid("""
                {"score": 100, "summary": "%s", "matchedSkills": [], "missingSkills": [], "gaps": [], "suggestions": []}
                """.formatted(hugeSummary));

        String manySkills = "\"skill\"," .repeat(MatchAnalysisValidator.MAX_SKILLS) + "\"one more\"";
        assertRetryableInvalid("""
                {"score": 100, "summary": "ok", "matchedSkills": [%s], "missingSkills": [], "gaps": [], "suggestions": []}
                """.formatted(manySkills));
    }

    @Test
    void skillBothMatchedAndMissingShouldBeRejected() {
        assertRetryableInvalid("""
                {"score": 50, "summary": "ok", "matchedSkills": ["Java"], "missingSkills": ["JAVA"], "gaps": [], "suggestions": []}
                """);
    }

    @Test
    void nonObjectOutputShouldBeRejected() {
        assertRetryableInvalid("[1, 2, 3]");
    }

    private static void assertRetryableInvalid(String output) {
        assertThatThrownBy(() -> MatchAnalysisValidator.validate(json(output)))
                .isInstanceOfSatisfying(MatchAnalysisException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(MatchAnalysisValidator.INVALID_OUTPUT);
                    assertThat(exception.isRetryable()).isTrue();
                });
    }
}
