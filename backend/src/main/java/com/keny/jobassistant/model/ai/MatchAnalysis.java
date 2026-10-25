package com.keny.jobassistant.model.ai;

import java.util.List;

/**
 * 匹配分析结果（存入 match_record.analysis_json）。分数单独存在 score 列，便于约束和排序。
 *
 * @param summary       一两句话的总体评价
 * @param matchedSkills 简历与 JD 都提到的技能
 * @param missingSkills JD 要求但简历中没有的技能
 * @param gaps          技能以外的差距，例如年限、领域经验
 * @param suggestions   针对这份 JD 的简历改写建议
 */
public record MatchAnalysis(
        String summary,
        List<String> matchedSkills,
        List<String> missingSkills,
        List<String> gaps,
        List<String> suggestions
) {
}
