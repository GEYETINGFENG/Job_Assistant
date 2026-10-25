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

        // "About the role" 写的是职责，保留；但公司口吻的介绍句不算要求。
        assertThat(requirements).containsExactly(
                "You will own critical services.",
                "3+ years of Java and Spring Boot",
                "Experience with Kafka or RabbitMQ",
                "Familiarity with AWS");
    }

    /** 本机 Test1 账号里真实 JD 的开头句：以前会被当成一条要求，在要求覆盖里显示为未覆盖。 */
    @Test
    void companyIntroductionSentencesShouldBeSkipped() {
        String description = """
                We are hiring a Machine Learning Engineer to build recommendation models. Our mission is to make search delightful.
                Join our team of 40 engineers.
                - 3+ years of Python and SQL
                At least two years of experience with PyTorch.
                We are looking for someone who has shipped models to production.
                Our stack is Python, Kafka and PostgreSQL.
                """;

        assertThat(extractor.extract(description)).containsExactly(
                "3+ years of Python and SQL",
                "At least two years of experience with PyTorch.",
                "We are looking for someone who has shipped models to production.",
                "Our stack is Python, Kafka and PostgreSQL.");
    }

    @Test
    void companyAndBenefitSectionsShouldBeSkippedUntilTheNextHeading() {
        String description = """
                About Acme
                Acme builds tools that help 10,000 teams ship faster every single day.
                Responsibilities:
                - Design and operate Kafka pipelines
                Benefits:
                - Unlimited paid time off and a generous learning budget
                Qualifications:
                - Experience with Kubernetes in production
                """;

        assertThat(extractor.extract(description)).containsExactly(
                "Design and operate Kafka pipelines",
                "Experience with Kubernetes in production");
    }

    @Test
    void numbersAtTheStartOfARequirementShouldNotBeTreatedAsBullets() {
        assertThat(extractor.extract("3.5+ years of experience with Go services"))
                .containsExactly("3.5+ years of experience with Go services");
    }

    @Test
    void chineseJobDescriptionShouldBeSplitBySectionAndPunctuation() {
        String description = """
                公司介绍：
                我们是一家专注于跨境支付的金融科技公司。
                岗位职责：
                1、负责支付核心系统的设计与开发；2、参与高并发场景的性能优化。
                任职要求：
                1、熟悉 Redis 缓存
                2、三年以上 Java 开发经验，熟悉 Spring Boot。
                福利待遇：
                - 五险一金，年终奖金和带薪年假
                """;

        assertThat(extractor.extract(description)).containsExactly(
                "负责支付核心系统的设计与开发",
                "参与高并发场景的性能优化。",
                "熟悉 Redis 缓存",
                "三年以上 Java 开发经验，熟悉 Spring Boot。");
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
