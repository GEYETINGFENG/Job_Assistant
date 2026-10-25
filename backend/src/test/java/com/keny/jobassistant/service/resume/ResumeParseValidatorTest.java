package com.keny.jobassistant.service.resume;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keny.jobassistant.common.ErrorCode;
import com.keny.jobassistant.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResumeParseValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SOURCE = """
            Alex Chen
            alex.chen@example.com | (415) 555-0100 | San Francisco, CA
            Backend engineer with six years building payment platforms.
            Skills: Java, Spring Boot, PostgreSQL, Kafka
            Paystream — Senior Backend Engineer (2021 – Present)
            Owned the card payment service handling 3M transactions per day.
            """;

    /** 模型按提示词返回的完整结构。 */
    private static ObjectNode valid() {
        return (ObjectNode) read("""
                {
                  "name": "Alex Chen",
                  "email": "alex.chen@example.com",
                  "phone": "415-555-0100",
                  "location": "San Francisco, CA",
                  "summary": "Backend engineer with six years building payment platforms.",
                  "skills": ["Java", "Spring Boot", "PostgreSQL", "Kafka"],
                  "education": [],
                  "experience": [
                    {"company": "Paystream", "position": "Senior Backend Engineer", "startDate": "2021", "endDate": "Present",
                     "description": "Owned the card payment service handling 3M transactions per day."}
                  ],
                  "projects": [],
                  "certificates": [],
                  "languages": ["English"]
                }
                """);
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void validOutputShouldPassUnchanged() {
        ObjectNode result = ResumeParseValidator.validate(valid(), SOURCE);

        assertThat(result.path("name").asText()).isEqualTo("Alex Chen");
        assertThat(result.path("email").asText()).isEqualTo("alex.chen@example.com");
        // 原文写 "(415) 555-0100"，按数字比较算出现过。
        assertThat(result.path("phone").asText()).isEqualTo("415-555-0100");
        assertThat(result.path("experience")).hasSize(1);
    }

    @Test
    void outputShouldBeNormalized() {
        ObjectNode output = valid();
        output.put("name", "  Alex Chen  ");
        output.putArray("skills").add("Java").add(" java ").add("").add("Kafka");
        // 提示词明确要求不要生成空占位对象，模型偶尔还是会生成。
        output.putArray("education").addObject()
                .put("school", "").put("degree", "").put("major", "")
                .put("startDate", "").put("endDate", "").put("description", "");
        output.put("internalNotes", "should not be stored");

        ObjectNode result = ResumeParseValidator.validate(output, SOURCE);

        assertThat(result.path("name").asText()).isEqualTo("Alex Chen");
        assertThat(result.path("skills")).extracting(JsonNode::asText).containsExactly("Java", "Kafka");
        assertThat(result.path("education")).isEmpty();
        assertThat(result.has("internalNotes")).isFalse();
    }

    @Test
    void nullStringFieldsShouldBecomeEmptyStrings() {
        ObjectNode output = valid();
        output.putNull("location");

        assertThat(ResumeParseValidator.validate(output, SOURCE).path("location").asText()).isEmpty();
    }

    /** 防编造：原文里没有的姓名、邮箱、电话一律清空，包括简历里藏的注入指令想塞进来的值。 */
    @Test
    void contactDetailsNotInSourceShouldBeCleared() {
        ObjectNode output = valid();
        output.put("name", "Jordan Smith");
        output.put("email", "attacker@evil.example");
        output.put("phone", "+1 212 555 0199");

        ObjectNode result = ResumeParseValidator.validate(output, SOURCE);

        assertThat(result.path("name").asText()).isEmpty();
        assertThat(result.path("email").asText()).isEmpty();
        assertThat(result.path("phone").asText()).isEmpty();
        // 其余内容不受影响。
        assertThat(result.path("skills")).hasSize(4);
    }

    @Test
    void malformedEmailShouldBeCleared() {
        ObjectNode output = valid();
        output.put("email", "alex.chen at example.com");

        assertThat(ResumeParseValidator.validate(output, SOURCE + " alex.chen at example.com").path("email").asText()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "skills", "experience", "languages"})
    void missingFieldShouldBeRejected(String field) {
        ObjectNode output = valid();
        output.remove(field);

        assertInvalid(output, field + " is missing");
    }

    @Test
    void wrongTypesShouldBeRejected() {
        ObjectNode skillsAsString = valid();
        skillsAsString.put("skills", "Java, Kafka");
        assertInvalid(skillsAsString, "skills must be an array");

        ObjectNode numericName = valid();
        numericName.put("name", 42);
        assertInvalid(numericName, "name must be a string");

        ObjectNode experienceAsStrings = valid();
        experienceAsStrings.putArray("experience").add("Paystream");
        assertInvalid(experienceAsStrings, "experience items must be objects");

        ObjectNode missingNestedField = valid();
        ((ObjectNode) missingNestedField.path("experience").get(0)).remove("company");
        assertInvalid(missingNestedField, "company is missing");
    }

    @Test
    void oversizedOutputShouldBeRejected() {
        ObjectNode tooManySkills = valid();
        var skills = tooManySkills.putArray("skills");
        for (int i = 0; i <= ResumeParseValidator.MAX_SKILLS; i++) skills.add("Skill " + i);
        assertInvalid(tooManySkills, "skills has too many items");

        ObjectNode longSummary = valid();
        longSummary.put("summary", "x".repeat(ResumeParseValidator.MAX_LONG_TEXT_LENGTH + 1));
        assertInvalid(longSummary, "summary is too long");
    }

    /** 原文有内容但模型什么都没抽出来：当作输出异常重试，而不是存一份空简历。 */
    @Test
    void emptyExtractionFromSubstantialResumeShouldBeRejected() {
        ObjectNode empty = (ObjectNode) read("""
                {"name": "", "email": "", "phone": "", "location": "", "summary": "", "skills": [],
                 "education": [], "experience": [], "projects": [], "certificates": [], "languages": []}
                """);

        assertInvalid(empty, "nothing was extracted");
        // 原文本身几乎为空时，空结果是合理的。
        assertThat(ResumeParseValidator.validate(empty, "Resume").path("skills")).isEmpty();
    }

    @Test
    void nonObjectOutputShouldBeRejected() {
        assertInvalid(read("[]"), "output must be a JSON object");
        assertInvalid(null, "output must be a JSON object");
    }

    private static void assertInvalid(JsonNode output, String message) {
        assertThatThrownBy(() -> ResumeParseValidator.validate(output, SOURCE))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    // SYSTEM_ERROR 由上传 worker 当作临时错误重试。
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.SYSTEM_ERROR.getCode());
                    assertThat(exception.getDescription()).contains(message);
                });
    }
}
