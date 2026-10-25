package com.keny.jobassistant.service.match;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 从简历版本的结构化内容中读取技能和全文，供两个分析器共用。
 */
public final class ResumeText {

    private ResumeText() {
    }

    /**
     * 简历中明确列出的技能：skills 加上各项目的 technologies，按大小写去重并保留原始写法。
     */
    public static Map<String, String> declaredSkills(JsonNode content) {
        Map<String, String> skills = new LinkedHashMap<>();
        addTextItems(content.path("skills"), skills);
        for (JsonNode project : content.path("projects")) {
            addTextItems(project.path("technologies"), skills);
        }
        return skills;
    }

    /**
     * 简历全部文本（小写），用于判断某个技能是否真的出现在简历里。
     */
    public static String searchableText(JsonNode content) {
        StringBuilder builder = new StringBuilder();
        collectText(content, builder);
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * 按"词边界"判断 text 中是否出现 term。普通 \b 处理不了 C++、C#、.NET 这类带符号的技术名，
     * 所以用"前后不是字母数字"作为边界。
     */
    public static boolean containsTerm(String lowerText, String term) {
        String normalized = term.toLowerCase(Locale.ROOT).strip();
        if (normalized.isEmpty()) {
            return false;
        }
        Pattern pattern = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(normalized) + "(?![a-z0-9])");
        return pattern.matcher(lowerText).find();
    }

    private static void addTextItems(JsonNode array, Map<String, String> target) {
        if (!array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            if (item.isTextual() && !item.asText().isBlank()) {
                String value = item.asText().strip();
                target.putIfAbsent(value.toLowerCase(Locale.ROOT), value);
            }
        }
    }

    private static void collectText(JsonNode node, StringBuilder builder) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            builder.append(node.asText()).append('\n');
        } else if (node.isContainerNode()) {
            node.forEach(child -> collectText(child, builder));
        }
    }
}
