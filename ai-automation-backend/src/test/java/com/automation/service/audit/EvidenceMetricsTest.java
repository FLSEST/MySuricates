package com.automation.service.audit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceMetricsTest {

    @Test
    void parsesNumbersWithKnownUnitsIntoCanonicalKeys() {
        Map<String, Double> metrics = EvidenceMetrics.parse(
                "WorkflowExecutor.java: 540 LOC, 12 deps, bundle 3,5 MB, coverage 80 %, 7 Zeilen doppelt");

        assertThat(metrics).containsEntry("LOC", 540.0)
                .containsEntry("deps", 12.0)
                .containsEntry("MB", 3.5)
                .containsEntry("%", 80.0);
    }

    @Test
    void keepsTheLargestValuePerUnitAndMergesSynonyms() {
        Map<String, Double> metrics = EvidenceMetrics.parse("A.java: 320 lines; B.java: 410 LOC; C.java: 150 Zeilen");

        assertThat(metrics).containsExactly(Map.entry("LOC", 410.0));
    }

    @Test
    void ignoresNumbersWithoutUnitAndVersionLikeTokens() {
        Map<String, Double> metrics = EvidenceMetrics.parse("Version 1.2 vom 2026-09-13, 3 Findings, v4.1 MB-Header");

        assertThat(metrics).isEmpty();
    }

    @Test
    void emptyOrNullEvidenceYieldsNoMetrics() {
        assertThat(EvidenceMetrics.parse(null)).isEmpty();
        assertThat(EvidenceMetrics.parse("   ")).isEmpty();
    }

    @Test
    void formatsIntegralValuesWithoutDecimals() {
        assertThat(EvidenceMetrics.format(320.0, "LOC")).isEqualTo("320 LOC");
        assertThat(EvidenceMetrics.format(3.5, "MB")).isEqualTo("3.5 MB");
        assertThat(EvidenceMetrics.format(80.0, "%")).isEqualTo("80 %");
    }
}
