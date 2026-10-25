package com.keny.jobassistant.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementExtractorTest {

    private final RequirementExtractor extractor = new RequirementExtractor();

    @Test
    void bulletsAndSentencesShouldBecomeRequirements() {
        String description = """
                About the role:
                We build payment infrastructure. You will own critical services.
                Requirements:
                - 3+ years of Java and Spring Boot
                • Experience with Kafka or RabbitMQ
                2) Familiarity with AWS
                - SQL
                """;

        List<String> requirements = extractor.extract(description);

        assertThat(requirements).containsExactly(
                "We build payment infrastructure.",
                "You will own critical services.",
                "3+ years of Java and Spring Boot",
                "Experience with Kafka or RabbitMQ",
                "Familiarity with AWS");
    }

    @Test
    void duplicatesShouldBeRemovedAndCountCapped() {
        StringBuilder description = new StringBuilder("- Experience with Kafka streams\n- Experience with Kafka streams\n");
        for (int i = 0; i < 30; i++) {
            description.append("- Requirement number ").append(i).append(" here\n");
        }

        List<String> requirements = extractor.extract(description.toString());

        assertThat(requirements).hasSize(RequirementExtractor.MAX_REQUIREMENTS).doesNotHaveDuplicates();
    }

    @Test
    void overlongLinesShouldBeTruncated() {
        List<String> requirements = extractor.extract("- " + "very long requirement ".repeat(30));

        assertThat(requirements.get(0)).hasSizeLessThanOrEqualTo(RequirementExtractor.MAX_LENGTH);
    }

    @Test
    void emptyInputShouldProduceNoRequirements() {
        assertThat(extractor.extract(null)).isEmpty();
        assertThat(extractor.extract("Apply now!")).isEmpty();
    }
}
