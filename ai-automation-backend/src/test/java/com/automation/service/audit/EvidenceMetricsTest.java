package com.automation.service.audit;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.OptionalInt;

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
    void parsesNclocAsItsOwnUnitNotAsLoc() {
        assertThat(EvidenceMetrics.parse("531 NCLOC (635 Zeilen), 6 deps"))
                .containsExactly(Map.entry("LOC", 635.0), Map.entry("NCLOC", 531.0), Map.entry("deps", 6.0));
        assertThat(EvidenceMetrics.parse("Big.java: 320 ncloc")).containsExactly(Map.entry("NCLOC", 320.0));
    }

    @Test
    void parsesNamedCountsFromFactLinesUnderTheirName() {
        Map<String, Double> metrics = EvidenceMetrics.parse(
                "FAKT unindexed_findby_columns_count: 9; TREFFER active (WorkflowMemoryRepository.java)");

        assertThat(metrics).containsExactly(Map.entry("unindexed_findby_columns_count", 9.0));
    }

    @Test
    void ignoresFactsThatAreNoNamedCount() {
        assertThat(EvidenceMetrics.parse("FAKT database_bytes: 335273984")).isEmpty();
        assertThat(EvidenceMetrics.parse("dead-code-scan.sh: deprecated_marker_count 5")).isEmpty();
        assertThat(EvidenceMetrics.parse("Foo_count: 3, count: 4, x_count: viele")).isEmpty();
    }

    @Test
    void churnIsNoMetricButReadSeparately() {
        assertThat(EvidenceMetrics.parse("Backend churn: 30; 6 Aenderungen in 90 Tagen")).isEmpty();

        assertThat(EvidenceMetrics.churn("531 NCLOC (635 Zeilen), 6 Aenderungen in 90 Tagen (Hotspot ab 5)"))
                .isEqualTo(OptionalInt.of(6));
        assertThat(EvidenceMetrics.churn("1 Aenderung in 90 Tagen (stabil, Hotspot ab 5)")).isEqualTo(OptionalInt.of(1));
        assertThat(EvidenceMetrics.churn("Backend churn: 30")).isEqualTo(OptionalInt.of(30));
        assertThat(EvidenceMetrics.churn("Struktur-Scan: 8 deps; Backend churn 10")).isEqualTo(OptionalInt.of(10));
        assertThat(EvidenceMetrics.churn("3 Änderungen seit Release")).isEqualTo(OptionalInt.of(3));
        assertThat(EvidenceMetrics.churn("4 changes in 90 days")).isEqualTo(OptionalInt.of(4));
        assertThat(EvidenceMetrics.churn("Struktur-Scan FRONTEND: 629 LOC")).isEmpty();
        assertThat(EvidenceMetrics.churn(null)).isEmpty();
    }

    @Test
    void readsTheHotspotThresholdOnlyWhenStated() {
        assertThat(EvidenceMetrics.hotspotThreshold("6 Aenderungen in 90 Tagen (Hotspot ab 5)")).isEqualTo(OptionalInt.of(5));
        assertThat(EvidenceMetrics.hotspotThreshold("1 Aenderung in 90 Tagen (stabil, Hotspot ab 8)")).isEqualTo(OptionalInt.of(8));
        assertThat(EvidenceMetrics.hotspotThreshold("Backend churn: 30")).isEmpty();
    }

    @Test
    void emptyOrNullEvidenceYieldsNoMetrics() {
        assertThat(EvidenceMetrics.parse(null)).isEmpty();
        assertThat(EvidenceMetrics.parse("   ")).isEmpty();
    }

    @Test
    void readsCveAndGhsaIdentifiersInOrderOfFirstAppearance() {
        assertThat(EvidenceMetrics.advisoryIds(
                "TREFFER trivy_jar: CRITICAL spring-webmvc-6.2.11.jar CVE-2026-47890 – SSE; "
                        + "TREFFER trivy: HIGH pom.xml GHSA-j9f9-w8pj-32f8, CVE-2026-47884 (XsltView), CVE-2026-47890"))
                .containsExactly("CVE-2026-47890", "GHSA-j9f9-w8pj-32f8", "CVE-2026-47884");
    }

    @Test
    void identifiersAreCanonicalisedSoCaseDoesNotCount() {
        assertThat(EvidenceMetrics.advisoryIds("cve-2026-47890, https://osv.dev/vulnerability/ghsa-J9F9-W8PJ-32F8"))
                .containsExactly("CVE-2026-47890", "GHSA-j9f9-w8pj-32f8");
        assertThat(EvidenceMetrics.advisoryIds("CVE-2026-47890 and Cve-2026-47890")).containsExactly("CVE-2026-47890");
    }

    @Test
    void ignoresTokensThatAreNoAdvisoryIdentifier() {
        assertThat(EvidenceMetrics.advisoryIds("CVE-26-47890, CVE-2026-123, XCVE-2026-47890, CVE-2026-47890x, "
                + "GHSA-j9f9-w8pj, GHSA_j9f9-w8pj-32f8, CWE-79, FAKT trivy_high: 2")).isEmpty();
        assertThat(EvidenceMetrics.advisoryIds(null)).isEmpty();
        assertThat(EvidenceMetrics.advisoryIds("  ")).isEmpty();
    }

    @Test
    void identifiersAreNoMeasurableValues() {
        assertThat(EvidenceMetrics.parse("TREFFER trivy: CVE-2026-47884, CVE-2026-47890, GHSA-j9f9-w8pj-32f8")).isEmpty();
    }

    @Test
    void formatsIntegralValuesWithoutDecimals() {
        assertThat(EvidenceMetrics.format(320.0, "LOC")).isEqualTo("320 LOC");
        assertThat(EvidenceMetrics.format(3.5, "MB")).isEqualTo("3.5 MB");
        assertThat(EvidenceMetrics.format(80.0, "%")).isEqualTo("80 %");
    }
}
