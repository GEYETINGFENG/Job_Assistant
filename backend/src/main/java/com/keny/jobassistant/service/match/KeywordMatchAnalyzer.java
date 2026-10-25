package com.keny.jobassistant.service.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.keny.jobassistant.model.ai.MatchAnalysis;
import com.keny.jobassistant.model.ai.MatchAnalysisResult;
import com.keny.jobassistant.model.ai.RequirementEvidence;
import com.keny.jobassistant.model.enums.MatchEngine;
import com.keny.jobassistant.service.MatchAnalyzer;
import com.keny.jobassistant.service.rag.TechnologyTerms;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * LLM 关闭时的关键词兜底匹配：确定性、免费，不依赖外部服务。
 * 只能比较技术关键词，不理解年限、领域经验等语义，结果标为 KEYWORD 让用户知道它的局限。
 */
@Service
@ConditionalOnExpression("!(${app.match.ai.enabled:false} and ${app.resume.ai.enabled:false})")
public class KeywordMatchAnalyzer implements MatchAnalyzer {

    // v2：结果新增 requirements（RAG 检索的逐条要求证据）。
    // v3：词表扩充，要求覆盖增加"点名的技术必须出现在证据里"的检查。
    static final String PROMPT_VERSION = "keyword-v3";
    private static final int MAX_SUGGESTIONS = 5;

    /** JD 中常见的技术关键词，用来找"JD 要求但简历没有"的技能；与 RAG 的证据检查共用一份词表。 */
    static final List<String> TECH_VOCABULARY = TechnologyTerms.VOCABULARY;

    @Override
    public MatchEngine engine() {
        return MatchEngine.KEYWORD;
    }

    @Override
    public String modelVersion() {
        return null;
    }

    @Override
    public String promptVersion() {
        return PROMPT_VERSION;
    }

    @Override
    public MatchAnalysisResult analyze(JsonNode resumeContent, String jobTitle, String jobDescription, List<RequirementEvidence> evidence) {
        String jobText = ((jobTitle == null ? "" : jobTitle) + "\n" + jobDescription).toLowerCase(Locale.ROOT);
        Map<String, String> resumeSkills = ResumeText.declaredSkills(resumeContent);
        String resumeText = ResumeText.searchableText(resumeContent);

        List<String> matched = new ArrayList<>();
        for (String skill : resumeSkills.values()) {
            if (ResumeText.containsTerm(jobText, skill)) {
                matched.add(skill);
            }
        }
        List<String> missing = new ArrayList<>();
        for (String term : TECH_VOCABULARY) {
            boolean inJob = ResumeText.containsTerm(jobText, term);
            boolean inResume = resumeSkills.containsKey(term.toLowerCase(Locale.ROOT)) || ResumeText.containsTerm(resumeText, term);
            if (inJob && !inResume) {
                missing.add(term);
            }
        }

        int compared = matched.size() + missing.size();
        int score = compared == 0 ? 0 : (int) Math.round(100.0 * matched.size() / compared);
        String summary = compared == 0
                ? "Keyword comparison found no technical keywords to compare. Enable AI analysis for a full assessment."
                : "Keyword comparison: %d of %d technical keywords from the job description appear in your resume."
                        .formatted(matched.size(), compared);
        List<String> suggestions = missing.stream()
                .limit(MAX_SUGGESTIONS)
                .map(term -> "If you have experience with " + term + ", mention it explicitly in your skills or projects.")
                .toList();

        MatchAnalysis analysis = new MatchAnalysis(summary, List.copyOf(matched), List.copyOf(missing), List.of(), suggestions,
                evidence == null ? List.of() : List.copyOf(evidence));
        return new MatchAnalysisResult(score, analysis, null, null);
    }
}
