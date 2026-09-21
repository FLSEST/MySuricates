package com.automation.entity;

import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AuditFindingTest {

    @Test
    void builderDefaults_describeAFreshlySeenFinding() {
        AuditFinding finding = AuditFinding.builder().projectId("p").auditKey("a").fingerprint("fp").build();

        assertThat(finding.getStatus()).isEqualTo(AuditFindingStatus.OPEN);
        assertThat(finding.getOccurrenceCount()).isEqualTo(1);
        assertThat(finding.getRegressionCount()).isZero();
        assertThat(finding.isUnknownRuleKey()).isFalse();
        assertThat(finding.isUnverifiedEvidence()).isFalse();
        assertThat(finding.getResolvedAt()).isNull();
        assertThat(finding.getDispositionAt()).isNull();
    }

    @Test
    void noArgsConstructor_alsoAppliesTheDefaults() {
        // @Builder.Default mit Feldinitialisierer: auch Hibernate (No-Args) sieht OPEN/1/0.
        AuditFinding finding = new AuditFinding();

        assertThat(finding.getStatus()).isEqualTo(AuditFindingStatus.OPEN);
        assertThat(finding.getOccurrenceCount()).isEqualTo(1);
        assertThat(finding.getRegressionCount()).isZero();
    }

    @Test
    void identity_isUniquePerProjectAuditAndFingerprint() {
        UniqueConstraint constraint = AuditFinding.class.getAnnotation(Table.class).uniqueConstraints()[0];

        assertThat(constraint.name()).isEqualTo("uq_audit_finding_fingerprint");
        assertThat(constraint.columnNames()).containsExactly("project_id", "audit_key", "fingerprint");
    }

    @Test
    void setters_carryTheDispositionSnapshot() {
        Instant at = Instant.parse("2026-09-13T10:00:00Z");
        AuditFinding finding = new AuditFinding();
        finding.setStatus(AuditFindingStatus.ACCEPTED_RISK);
        finding.setDispositionNote("Split geplant");
        finding.setDispositionEvidence("Big.java: 320 LOC");
        finding.setDispositionAt(at);

        assertThat(finding.getStatus().isTerminal()).isTrue();
        assertThat(finding.getDispositionEvidence()).isEqualTo("Big.java: 320 LOC");
        assertThat(finding.getDispositionAt()).isEqualTo(at);
    }
}
