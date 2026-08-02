package com.keny.jobassistant.model.ai;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;
import java.util.Optional;

/**
 * AI 解析后的结构化简历数据。
 * 使用固定的数据结构接收阿里云百炼返回的 JSON，避免直接保存结构不确定的数据。
 */
@JsonClassDescription("Structured information extracted from a resume")
//给这个类添加描述信息
public class ResumeParsedData {

    @JsonPropertyDescription("Candidate full name")
    public Optional<String> name;

    @JsonPropertyDescription("Candidate email address")
    public Optional<String> email;

    @JsonPropertyDescription("Candidate phone number")
    public Optional<String> phone;

    @JsonPropertyDescription("Candidate current location")
    public Optional<String> location;

    @JsonPropertyDescription("Short professional summary")
    public Optional<String> summary;

    @JsonPropertyDescription("Candidate skills")
    public List<String> skills;

    @JsonPropertyDescription("Education history")
    public List<Education> education;

    @JsonPropertyDescription("Work experience")
    public List<Experience> experience;

    @JsonPropertyDescription("Project experience")
    public List<Project> projects;

    @JsonPropertyDescription("Certificates and awards")
    public List<Certificate> certificates;

    @JsonPropertyDescription("Languages used by the candidate")
    public List<String> languages;

    /**
     * 教育经历。
     */
    public static class Education {

        public Optional<String> school;
        public Optional<String> degree;
        public Optional<String> major;
        public Optional<String> startDate;
        public Optional<String> endDate;
        public Optional<String> description;
    }

    /**
     * 工作经历。
     */
    public static class Experience {

        public Optional<String> company;
        public Optional<String> position;
        public Optional<String> startDate;
        public Optional<String> endDate;
        public Optional<String> description;
    }

    /**
     * 项目经历。
     */
    public static class Project {

        public Optional<String> name;
        public Optional<String> role;
        public Optional<String> startDate;
        public Optional<String> endDate;
        public Optional<String> description;
        public List<String> technologies;
    }

    /**
     * 证书或奖项。
     */
    public static class Certificate {

        public Optional<String> name;
        public Optional<String> issuer;
        public Optional<String> date;
        public Optional<String> description;
    }
}