package com.keny.jobassistant.model.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 转移表是纯逻辑，用单元测试覆盖全部合法转移和关键的非法转移。 */
class ApplicationStatusTest {

    @ParameterizedTest
    @CsvSource({
            "APPLIED, SCREENING", "APPLIED, REJECTED", "APPLIED, WITHDRAWN",
            "SCREENING, INTERVIEW", "SCREENING, REJECTED", "SCREENING, WITHDRAWN",
            "INTERVIEW, OFFER", "INTERVIEW, REJECTED", "INTERVIEW, WITHDRAWN",
            "OFFER, ACCEPTED", "OFFER, REJECTED"
    })
    void legalTransitionsShouldBeAllowed(ApplicationStatus from, ApplicationStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            // 生命周期不可逆。
            "OFFER, APPLIED", "INTERVIEW, SCREENING", "SCREENING, APPLIED",
            // 不能跳过中间阶段。
            "APPLIED, OFFER", "APPLIED, ACCEPTED", "SCREENING, OFFER",
            // 拿到 offer 后只能接受或拒绝。
            "OFFER, WITHDRAWN"
    })
    void illegalTransitionsShouldBeRejected(ApplicationStatus from, ApplicationStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ApplicationStatus.class, names = {"ACCEPTED", "REJECTED", "WITHDRAWN"})
    void terminalStatusesShouldHaveNoOutgoingTransitions(ApplicationStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(terminal.allowedNext()).isEmpty();
        for (ApplicationStatus target : ApplicationStatus.values()) {
            assertThat(terminal.canTransitionTo(target)).isFalse();
        }
    }

    @ParameterizedTest
    @EnumSource(ApplicationStatus.class)
    void statusShouldNeverTransitionToItselfOrNull(ApplicationStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
        assertThat(status.canTransitionTo(null)).isFalse();
    }

    @Test
    void everyNonTerminalStatusShouldEventuallyReachATerminalStatus() {
        for (ApplicationStatus status : ApplicationStatus.values()) {
            assertThat(status.isTerminal() || status.allowedNext().stream().anyMatch(ApplicationStatus::isTerminal))
                    .as("%s should have a direct path to a terminal status", status)
                    .isTrue();
        }
    }
}
