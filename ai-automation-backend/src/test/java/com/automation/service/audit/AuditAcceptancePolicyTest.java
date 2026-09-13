package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditSeverity;
import com.automation.service.audit.AuditAcceptancePolicy.Outcome;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuditAcceptancePolicyTest {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-03-01T10:00:00Z");

    private final AuditAcceptancePolicy policy = new AuditAcceptancePolicy();

    private static AuditFinding accepted(String snapshotEvidence, Instant acceptedAt) {
        return AuditFinding.builder()
                .id("f1").projectId("p1").auditKey("k").fingerprint("fp").ruleKey("size.class").subject("Big.java")
                .severity(AuditSeverity.HIGH).title("Big.java ist zu gross").evidence("Big.java: 320 LOC")
                .status(AuditFindingStatus.ACCEPTED_RISK).dispositionNote("Split geplant")
                .dispositionEvidence(snapshotEvidence).dispositionAt(acceptedAt).resolvedAt(acceptedAt)
                .firstSeenReportId("r0").lastSeenReportId("r0").firstSeenRunId("run0").lastSeenRunId("run0")
                .firstSeenAt(ACCEPTED_AT).lastSeenAt(ACCEPTED_AT).build();
    }

    @Test
    void accept_snapshotsCurrentEvidenceAndTimestamp() {
        AuditFinding finding = accepted(null, null);
        finding.setEvidence("Big.java: 333 LOC");

        policy.accept(finding, ACCEPTED_AT);

        assertThat(finding.getDispositionEvidence()).isEqualTo("Big.java: 333 LOC");
        assertThat(finding.getDispositionAt()).isEqualTo(ACCEPTED_AT);
    }

    @Test
    void reassess_degradationAboveTenPercent_reopensAndAnnotates() {
        AuditFinding finding = accepted("Big.java: 320 LOC", ACCEPTED_AT);

        Outcome outcome = policy.reassess(finding, "Big.java: 400 LOC", ACCEPTED_AT.plus(Duration.ofDays(7)));

        assertThat(outcome).isEqualTo(Outcome.REOPENED);
        assertThat(finding.getStatus()).isEqualTo(AuditFindingStatus.OPEN);
        assertThat(finding.getResolvedAt()).isNull();
        assertThat(finding.getDispositionNote()).isEqualTo("Split geplant\nVerschlechtert: 320 LOC → 400 LOC");
        assertThat(finding.getDispositionEvidence()).isEqualTo("Big.java: 320 LOC");
    }

    @Test
    void reassess_degradationAtOrBelowTenPercent_staysAccepted() {
        AuditFinding finding = accepted("Big.java: 320 LOC", ACCEPTED_AT);

        Outcome outcome = policy.reassess(finding, "Big.java: 352 LOC", ACCEPTED_AT.plus(Duration.ofDays(7)));

        assertThat(outcome).isEqualTo(Outcome.UNCHANGED);
        assertThat(finding.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(finding.getDispositionNote()).isEqualTo("Split geplant");
    }

    @Test
    void reassess_withoutComparableNumbers_staysAccepted() {
        AuditFinding finding = accepted("Klasse ist unuebersichtlich", ACCEPTED_AT);

        assertThat(policy.reassess(finding, "Klasse ist noch unuebersichtlicher", ACCEPTED_AT.plus(Duration.ofDays(7))))
                .isEqualTo(Outcome.UNCHANGED);
        assertThat(policy.reassess(accepted(null, ACCEPTED_AT), "Big.java: 900 LOC", ACCEPTED_AT.plus(Duration.ofDays(7))))
                .isEqualTo(Outcome.UNCHANGED);
        assertThat(policy.reassess(accepted("12 deps", ACCEPTED_AT), "Big.java: 900 LOC", ACCEPTED_AT.plus(Duration.ofDays(7))))
                .as("unit only present on one side is not comparable").isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void reassess_olderThan180Days_movesToAwaitingVerificationOnce() {
        AuditFinding finding = accepted("Big.java: 320 LOC", ACCEPTED_AT);

        Outcome first = policy.reassess(finding, "Big.java: 320 LOC", ACCEPTED_AT.plus(Duration.ofDays(181)));
        Outcome second = policy.reassess(finding, "Big.java: 320 LOC", ACCEPTED_AT.plus(Duration.ofDays(400)));

        assertThat(first).isEqualTo(Outcome.REVIEW_DUE);
        assertThat(finding.getStatus()).isEqualTo(AuditFindingStatus.AWAITING_VERIFICATION);
        assertThat(finding.getDispositionNote()).isEqualTo("Split geplant\nWiedervorlage nach 180 Tagen");
        assertThat(finding.getDispositionEvidence()).isEqualTo("Big.java: 320 LOC");
        assertThat(second).as("no longer ACCEPTED_RISK, normal rules apply").isEqualTo(Outcome.UNCHANGED);
    }

    @Test
    void reassess_youngerThan180Days_staysAccepted() {
        AuditFinding finding = accepted("Big.java: 320 LOC", ACCEPTED_AT);

        assertThat(policy.reassess(finding, "Big.java: 320 LOC", ACCEPTED_AT.plus(Duration.ofDays(179))))
                .isEqualTo(Outcome.UNCHANGED);
        assertThat(finding.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
    }

    @Test
    void reassess_degradationWinsOverExpiry() {
        AuditFinding finding = accepted("Big.java: 320 LOC", ACCEPTED_AT);

        assertThat(policy.reassess(finding, "Big.java: 500 LOC", ACCEPTED_AT.plus(Duration.ofDays(400))))
                .isEqualTo(Outcome.REOPENED);
        assertThat(finding.getStatus()).isEqualTo(AuditFindingStatus.OPEN);
    }

    @Test
    void reassess_fallsBackToResolvedAtForLegacyAcceptancesWithoutDispositionAt() {
        AuditFinding finding = accepted(null, null);
        finding.setResolvedAt(ACCEPTED_AT);

        assertThat(policy.reassess(finding, "Big.java: 320 LOC", ACCEPTED_AT.plus(Duration.ofDays(200))))
                .isEqualTo(Outcome.REVIEW_DUE);
    }

    @Test
    void reassess_isNoopForOtherStatuses() {
        AuditFinding finding = accepted("Big.java: 320 LOC", ACCEPTED_AT);
        finding.setStatus(AuditFindingStatus.DISMISSED);

        assertThat(policy.reassess(finding, "Big.java: 900 LOC", ACCEPTED_AT.plus(Duration.ofDays(400))))
                .isEqualTo(Outcome.UNCHANGED);
        assertThat(finding.getStatus()).isEqualTo(AuditFindingStatus.DISMISSED);
    }
}
