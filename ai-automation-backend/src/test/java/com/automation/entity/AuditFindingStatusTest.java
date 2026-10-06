package com.automation.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/** isTerminal decides whether a managed TODO is closed and whether a finding counts as open. */
class AuditFindingStatusTest {

    @ParameterizedTest
    @EnumSource(value = AuditFindingStatus.class, names = {"RESOLVED", "ACCEPTED_RISK", "DISMISSED"})
    void isTerminal_trueForClosedStates(AuditFindingStatus status) {
        assertThat(status.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = AuditFindingStatus.class, names = {"OPEN", "AWAITING_VERIFICATION"})
    void isTerminal_falseForStatesThatStillNeedWork(AuditFindingStatus status) {
        assertThat(status.isTerminal()).isFalse();
    }

    @Test
    void values_areExactlyTheFiveKnownStates() {
        // New values must be deliberately assigned to one of the two isTerminal branches.
        assertThat(AuditFindingStatus.values()).containsExactly(
                AuditFindingStatus.OPEN, AuditFindingStatus.AWAITING_VERIFICATION,
                AuditFindingStatus.RESOLVED, AuditFindingStatus.ACCEPTED_RISK, AuditFindingStatus.DISMISSED);
    }
}
