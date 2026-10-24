package com.keny.jobassistant.model.enums;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 求职申请状态。
 * 合法转移只在下面的 TRANSITIONS 表中定义，是状态规则的单一事实来源。
 */
public enum ApplicationStatus {

    // 已投递。
    APPLIED,

    // 简历筛选中。
    SCREENING,

    // 面试中。
    INTERVIEW,

    // 拿到 offer。
    OFFER,

    // 已接受 offer，终态。
    ACCEPTED,

    // 被拒绝或拒绝 offer，终态。
    REJECTED,

    // 用户主动撤回，终态。
    WITHDRAWN;

    private static final Map<ApplicationStatus, Set<ApplicationStatus>> TRANSITIONS =
            new EnumMap<>(ApplicationStatus.class);

    static {
        TRANSITIONS.put(APPLIED, EnumSet.of(SCREENING, REJECTED, WITHDRAWN));
        TRANSITIONS.put(SCREENING, EnumSet.of(INTERVIEW, REJECTED, WITHDRAWN));
        TRANSITIONS.put(INTERVIEW, EnumSet.of(OFFER, REJECTED, WITHDRAWN));
        TRANSITIONS.put(OFFER, EnumSet.of(ACCEPTED, REJECTED));
        // 终态没有任何出边：生命周期不可逆，例如 OFFER 不能回到 APPLIED。
        TRANSITIONS.put(ACCEPTED, EnumSet.noneOf(ApplicationStatus.class));
        TRANSITIONS.put(REJECTED, EnumSet.noneOf(ApplicationStatus.class));
        TRANSITIONS.put(WITHDRAWN, EnumSet.noneOf(ApplicationStatus.class));
    }

    /**
     * 当前状态允许转移到的下一个状态。
     */
    public Set<ApplicationStatus> allowedNext() {
        return Collections.unmodifiableSet(TRANSITIONS.get(this));
    }

    public boolean canTransitionTo(ApplicationStatus target) {
        return target != null && TRANSITIONS.get(this).contains(target);
    }

    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }
}
