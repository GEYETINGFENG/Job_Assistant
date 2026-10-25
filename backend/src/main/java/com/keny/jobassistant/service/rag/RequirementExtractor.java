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
 *
 * 公司介绍不是要求：拿去检索只会得到"没有证据"，在要求覆盖里白白算一条未覆盖。所以
 * "About us"、"Benefits"、"公司介绍"这类段落整段跳过，其余位置以公司口吻开头的句子
 * （"We are hiring…"、"Our mission…"）也丢弃。
 */
@Component
public class RequirementExtractor {

    public static final int MAX_REQUIREMENTS = 15;
    static final int MIN_LENGTH = 15;
    /** 中文信息密度高，"熟悉 Redis 缓存"这样的短句也是一条完整要求。 */
    static final int MIN_LENGTH_CJK = 6;
    static final int MAX_LENGTH = 300;
    /** 超过这个长度的行不当作标题，即使它以冒号结尾。 */
    private static final int MAX_HEADING_LENGTH = 40;

    private static final Pattern BULLET = Pattern.compile("^\\s*(?:(?:[-*•·▪]|\\d+[.)])\\s+|\\d+、\\s*)");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[.!?;])\\s+|(?<=[。！？；])");
    /** 中文 JD 常把几条要求写在一行："1、负责…；2、参与…"。 */
    private static final Pattern INLINE_ITEM = Pattern.compile("(?<=[；;。])\\s*(?=\\d+、)");
    private static final Pattern CJK = Pattern.compile("\\p{IsHan}");

    /** 这些段落介绍公司、团队或待遇，里面的内容不是对候选人的要求。 */
    private static final Pattern NON_REQUIREMENT_SECTION = Pattern.compile(
            "^(about (us|the company|the team|[a-z0-9 &.-]{1,30})|who we are|our (company|story|mission|team|culture)"
                    + "|benefits|perks|what we offer|why join us|why work (with|for) us|compensation( and benefits)?"
                    + "|equal (employment )?opportunity.*|how to apply"
                    + "|公司介绍|公司简介|关于我们|团队介绍|福利待遇|薪资福利|福利|我们提供|加入我们)$");

    /** 例外："About the role" 一般写的是职责，保留。 */
    private static final Pattern ROLE_SECTION = Pattern.compile("^about (the|this) (role|position|job)$");

    /**
     * 以公司口吻开头的介绍句。故意不包括 "We are looking for…"（后面通常就是要求）
     * 和 "Our stack is…"（描述技术栈，相当于要求）。
     */
    private static final Pattern COMPANY_VOICE = Pattern.compile(
            "^(we are|we're) (hiring|a |an |the |building|on a mission|proud|growing)|^we (build|make|help|believe|offer|provide)\\b"
                    + "|^our (mission|vision|company|customers|culture|story)\\b|^join (us|our)\\b|^founded in\\b"
                    + "|^(apply|equal opportunity)\\b"
                    + "|^(我们是|我们正在招聘|公司成立于|公司是|欢迎加入)",
            Pattern.CASE_INSENSITIVE);

    public List<String> extract(String jobDescription) {
        Set<String> requirements = new LinkedHashSet<>();
        if (jobDescription == null) {
            return List.of();
        }
        boolean skippingSection = false;
        for (String rawLine : jobDescription.split("\\R")) {
            if (rawLine.isBlank()) {
                continue;
            }
            boolean bullet = BULLET.matcher(rawLine).find();
            String line = BULLET.matcher(rawLine).replaceFirst("").strip();
            if (!bullet) {
                String heading = heading(line);
                if (heading != null) {
                    skippingSection = NON_REQUIREMENT_SECTION.matcher(heading).matches()
                            && !ROLE_SECTION.matcher(heading).matches();
                    continue;
                }
            }
            if (skippingSection) {
                continue;
            }
            List<String> candidates = new ArrayList<>();
            for (String item : INLINE_ITEM.split(line)) {
                String text = BULLET.matcher(item).replaceFirst("");
                candidates.addAll(bullet ? List.of(text) : List.of(SENTENCE_BOUNDARY.split(text)));
            }
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

    /**
     * 标题行返回去掉冒号、小写后的标题，否则返回 null。
     * 以冒号结尾的短行一定是标题；不带冒号的短行只有是已知段落名时才算（例如单独一行的 "Benefits"）。
     */
    private static String heading(String line) {
        if (line.length() > MAX_HEADING_LENGTH) {
            return null;
        }
        boolean colon = line.endsWith(":") || line.endsWith("：");
        String title = line.replaceAll("[:：]$", "").strip().toLowerCase(Locale.ROOT);
        if (colon || NON_REQUIREMENT_SECTION.matcher(title).matches()) {
            return title;
        }
        return null;
    }

    private static String clean(String text) {
        String value = text.strip().replaceAll("[；;]+$", "");
        if (value.length() > MAX_LENGTH) {
            value = value.substring(0, MAX_LENGTH).strip();
        }
        return value;
    }

    private static boolean isRequirement(String text) {
        int minLength = CJK.matcher(text).find() ? MIN_LENGTH_CJK : MIN_LENGTH;
        if (text.length() < minLength) {
            return false;
        }
        if (text.endsWith(":") || text.endsWith("：")) {
            return false;
        }
        return !COMPANY_VOICE.matcher(text).find();
    }
}
