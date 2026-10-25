package com.keny.jobassistant.service.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.keny.jobassistant.exception.MatchAnalysisException;
import com.keny.jobassistant.model.ai.MatchAnalysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LLM 输出的业务校验。
 * JSON Mode 只保证是合法 JSON，反序列化也很宽容（缺字段给 null 也算成功）；
 * 这里检查字段是否齐全、类型是否正确、数值和长度是否合理，不通过就当作解析失败重试。
 * 长度和条数上限同时限制了 prompt 注入成功时能塞进结果的内容。
 */
public final class MatchAnalysisValidator {

    public static final String INVALID_OUTPUT = "LLM_INVALID_OUTPUT";

    static final int MAX_SUMMARY_LENGTH = 600;
    static final int MAX_SKILLS = 30;
    static final int MAX_SKILL_LENGTH = 80;
    static final int MAX_TEXT_ITEMS = 10;
    static final int MAX_TEXT_ITEM_LENGTH = 400;

    private MatchAnalysisValidator() {
    }

    public record ValidatedAnalysis(int score, MatchAnalysis analysis) {
    }

    public static ValidatedAnalysis validate(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid("Output must be a JSON object");
        }
        int score = readScore(node.get("score"));
        String summary = readText(node.get("summary"), "summary", MAX_SUMMARY_LENGTH);
        List<String> matched = readList(node.get("matchedSkills"), "matchedSkills", MAX_SKILLS, MAX_SKILL_LENGTH, true);
        List<String> missing = readList(node.get("missingSkills"), "missingSkills", MAX_SKILLS, MAX_SKILL_LENGTH, true);
        List<String> gaps = readList(node.get("gaps"), "gaps", MAX_TEXT_ITEMS, MAX_TEXT_ITEM_LENGTH, false);
        List<String> suggestions = readList(node.get("suggestions"), "suggestions", MAX_TEXT_ITEMS, MAX_TEXT_ITEM_LENGTH, false);

        // 同一个技能不可能既匹配又缺失。
        List<String> matchedLower = matched.stream().map(skill -> skill.toLowerCase(Locale.ROOT)).toList();
        if (missing.stream().anyMatch(skill -> matchedLower.contains(skill.toLowerCase(Locale.ROOT)))) {
            throw invalid("A skill cannot be both matched and missing");
        }
        return new ValidatedAnalysis(score, new MatchAnalysis(summary, matched, missing, gaps, suggestions));
    }

    private static int readScore(JsonNode node) {
        if (node == null || !node.isNumber()) {
            throw invalid("score must be a number");
        }
        double value = node.asDouble();
        if (value != Math.rint(value)) {
            throw invalid("score must be an integer");
        }
        if (value < 0 || value > 100) {
            throw invalid("score must be between 0 and 100");
        }
        return (int) value;
    }

    private static String readText(JsonNode node, String field, int maxLength) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw invalid(field + " must be a non-empty string");
        }
        String value = node.asText().strip();
        if (value.length() > maxLength) {
            throw invalid(field + " is too long");
        }
        return value;
    }

    /**
     * 数组必须存在（可以为空），元素必须是非空字符串；技能列表按大小写去重。
     */
    private static List<String> readList(JsonNode node, String field, int maxItems, int maxLength, boolean dedupe) {
        if (node == null || !node.isArray()) {
            throw invalid(field + " must be an array");
        }
        if (node.size() > maxItems) {
            throw invalid(field + " has too many items");
        }
        Map<String, String> values = new LinkedHashMap<>();
        List<String> ordered = new ArrayList<>();
        for (JsonNode item : node) {
            String value = readText(item, field + " item", maxLength);
            if (dedupe) {
                values.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
            } else {
                ordered.add(value);
            }
        }
        return dedupe ? List.copyOf(values.values()) : List.copyOf(ordered);
    }

    private static MatchAnalysisException invalid(String message) {
        return new MatchAnalysisException(INVALID_OUTPUT, "AI returned an invalid match analysis: " + message, true);
    }
}
