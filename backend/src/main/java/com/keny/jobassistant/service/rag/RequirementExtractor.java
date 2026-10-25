package com.keny.jobassistant.service.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把 JD 拆成逐条要求，每条单独去简历里找证据。
 * 项目符号行本身就是一条要求；普通段落按句子拆；标题行（"Requirements:"）和过短的行丢弃。
 */
@Component
public class RequirementExtractor {

    public static final int MAX_REQUIREMENTS = 15;
    static final int MIN_LENGTH = 15;
    static final int MAX_LENGTH = 300;

    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*•·▪]|\\d+[.)])\\s+");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?;])\\s+");

    public List<String> extract(String jobDescription) {
        Set<String> requirements = new LinkedHashSet<>();
        if (jobDescription == null) {
            return List.of();
        }
        for (String rawLine : jobDescription.split("\\R")) {
            if (rawLine.isBlank()) {
                continue;
            }
            boolean bullet = BULLET.matcher(rawLine).find();
            String line = BULLET.matcher(rawLine).replaceFirst("").strip();
            List<String> candidates = bullet ? List.of(line) : List.of(SENTENCE_BOUNDARY.split(line));
            for (String candidate : candidates) {
                String requirement = clean(candidate);
                if (isRequirement(requirement)) {
                    requirements.add(requirement);
                }
                if (requirements.size() >= MAX_REQUIREMENTS) {
                    return new ArrayList<>(requirements);
                }
            }
        }
        return new ArrayList<>(requirements);
    }

    private static String clean(String text) {
        String value = text.strip();
        if (value.length() > MAX_LENGTH) {
            value = value.substring(0, MAX_LENGTH).strip();
        }
        return value;
    }

    private static boolean isRequirement(String text) {
        if (text.length() < MIN_LENGTH) {
            return false;
        }
        // "Requirements:"、"What you'll do:" 这类标题行。
        if (text.endsWith(":")) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return !lower.startsWith("about us") && !lower.startsWith("about the role");
    }
}
