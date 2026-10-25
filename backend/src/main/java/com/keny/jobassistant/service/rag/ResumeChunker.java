package com.keny.jobassistant.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * 把简历切成检索单元。
 *
 * 简历本身是结构化的：一段工作经历、一个项目就是一个完整的语义单元。
 * 按结构切块能保证"一条证据"不被切断，也不会把两段不相关的经历混在同一块里；
 * 只有原文、没有结构化内容的旧版本才退回固定长度切块。
 */
@Component
public class ResumeChunker {

    public static final int DEFAULT_FIXED_SIZE = 800;
    public static final int DEFAULT_FIXED_OVERLAP = 150;

    public List<ResumeChunk> chunk(JsonNode content) {
        List<ResumeChunk> chunks = structuredChunks(content);
        if (!chunks.isEmpty()) {
            return chunks;
        }
        return fixedSize(text(content.path("rawText")), DEFAULT_FIXED_SIZE, DEFAULT_FIXED_OVERLAP);
    }

    /**
     * 按段落切块；没有任何结构化内容时返回空列表。
     */
    public List<ResumeChunk> structuredChunks(JsonNode content) {
        List<ResumeChunk> chunks = new ArrayList<>();
        if (content == null || !content.isObject()) {
            return chunks;
        }
        String summary = text(content.path("summary"));
        if (!summary.isEmpty()) {
            add(chunks, "summary", ChunkSection.SUMMARY, "Summary: " + summary);
        }

        String skills = joinTextItems(content.path("skills"));
        String languages = joinTextItems(content.path("languages"));
        if (!skills.isEmpty() || !languages.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            if (!skills.isEmpty()) builder.append("Skills: ").append(skills);
            if (!languages.isEmpty()) builder.append(builder.isEmpty() ? "" : "\n").append("Languages: ").append(languages);
            add(chunks, "skills", ChunkSection.SKILLS, builder.toString());
        }

        int index = 0;
        for (JsonNode item : content.path("experience")) {
            String header = "Experience: " + joinNonEmpty(" at ", text(item.path("position")), text(item.path("company")))
                    + dates(item);
            String body = text(item.path("description"));
            if (hasContent(header, "Experience: ") || !body.isEmpty()) {
                add(chunks, "experience:" + index, ChunkSection.EXPERIENCE, withBody(header, body));
            }
            index++;
        }

        index = 0;
        for (JsonNode item : content.path("projects")) {
            String role = text(item.path("role"));
            String header = "Project: " + text(item.path("name")) + (role.isEmpty() ? "" : " (" + role + ")") + dates(item);
            String technologies = joinTextItems(item.path("technologies"));
            String body = joinNonEmpty("\n", technologies.isEmpty() ? "" : "Technologies: " + technologies, text(item.path("description")));
            if (hasContent(header, "Project: ") || !body.isEmpty()) {
                add(chunks, "project:" + index, ChunkSection.PROJECT, withBody(header, body));
            }
            index++;
        }

        index = 0;
        for (JsonNode item : content.path("education")) {
            String degree = joinNonEmpty(" in ", text(item.path("degree")), text(item.path("major")));
            String header = "Education: " + joinNonEmpty(", ", degree, text(item.path("school"))) + dates(item);
            String body = text(item.path("description"));
            if (hasContent(header, "Education: ") || !body.isEmpty()) {
                add(chunks, "education:" + index, ChunkSection.EDUCATION, withBody(header, body));
            }
            index++;
        }

        StringJoiner certificates = new StringJoiner("; ");
        for (JsonNode item : content.path("certificates")) {
            String detail = joinNonEmpty(", ", text(item.path("issuer")), text(item.path("date")));
            String name = text(item.path("name"));
            if (!name.isEmpty()) {
                certificates.add(detail.isEmpty() ? name : name + " (" + detail + ")");
            }
        }
        if (certificates.length() > 0) {
            add(chunks, "certificates", ChunkSection.CERTIFICATE, "Certificates: " + certificates);
        }
        return chunks;
    }

    /**
     * 固定长度切块：每块 size 个字符，相邻块重叠 overlap 个字符，防止一句话正好被切在边界上。
     * 尽量在空白处断开，避免把单词切成两半。
     */
    public static List<ResumeChunk> fixedSize(String text, int size, int overlap) {
        if (size <= 0 || overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("Chunk size must be positive and larger than the overlap");
        }
        List<ResumeChunk> chunks = new ArrayList<>();
        String normalized = text == null ? "" : text.strip();
        int start = 0;
        int ordinal = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + size);
            if (end < normalized.length()) {
                int lastSpace = normalized.lastIndexOf(' ', end);
                if (lastSpace > start + size / 2) {
                    end = lastSpace;
                }
            }
            String piece = normalized.substring(start, end).strip();
            if (!piece.isEmpty()) {
                chunks.add(new ResumeChunk("text:" + ordinal, ChunkSection.TEXT, ordinal, piece));
                ordinal++;
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
        return chunks;
    }

    private static void add(List<ResumeChunk> chunks, String key, ChunkSection section, String content) {
        chunks.add(new ResumeChunk(key, section, chunks.size(), content));
    }

    private static String withBody(String header, String body) {
        return body.isEmpty() ? header : header + "\n" + body;
    }

    private static boolean hasContent(String header, String label) {
        return !header.substring(label.length()).isBlank();
    }

    private static String dates(JsonNode item) {
        String range = joinNonEmpty(" - ", text(item.path("startDate")), text(item.path("endDate")));
        return range.isEmpty() ? "" : " (" + range + ")";
    }

    private static String joinTextItems(JsonNode array) {
        StringJoiner joiner = new StringJoiner(", ");
        if (array.isArray()) {
            for (JsonNode item : array) {
                String value = text(item);
                if (!value.isEmpty()) joiner.add(value);
            }
        }
        return joiner.toString();
    }

    private static String joinNonEmpty(String separator, String... values) {
        StringJoiner joiner = new StringJoiner(separator);
        for (String value : values) {
            if (!value.isEmpty()) joiner.add(value);
        }
        return joiner.toString();
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText().strip() : "";
    }
}
