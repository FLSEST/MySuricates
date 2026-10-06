package com.automation.service.audit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** A finding must rely on a measurement of this run (TODO a94b56a0). */
class EvidenceVerificationTest {

    private static final String FACT_SHEET = """
            ## FAKTENBLATT
            FAKT files_over_300: 19
            FAKT non_main_runtime_timeout_lines: 4
            NICHT GEMESSEN: step_results_bytes (Shell-Bloecke duerfen die DB nicht lesen)
            TREFFER repository_in_loop: mapper/WishMapper.java:36 workflowRepository.find

            ## PFLICHTFELDER
            PFLICHT-FAKTEN: database_bytes source_files_scanned
            """;

    @Test
    void sammeltFaktUndTrefferNamenAusDemFaktenblatt() {
        Set<String> names = EvidenceVerification.factNames(List.of(FACT_SHEET));

        assertThat(names).containsExactlyInAnyOrder(
                "files_over_300", "non_main_runtime_timeout_lines", "repository_in_loop");
    }

    @Test
    void ignoriertAusgabenOhneFaktenblattKopf() {
        assertThat(EvidenceVerification.factNames(List.of("FAKT erfunden: 1"))).isEmpty();
        assertThat(EvidenceVerification.factNames(null)).isEmpty();
    }

    @Test
    void belegMitEinemMesswertDesLaufsGiltAlsGedeckt() {
        Set<String> names = EvidenceVerification.factNames(List.of(FACT_SHEET));

        assertThat(EvidenceVerification.isSupported("FAKT files_over_300: 19", names)).isTrue();
        assertThat(EvidenceVerification.isSupported(
                "TREFFER repository_in_loop: mapper/WishMapper.java:36", names)).isTrue();
    }

    @Test
    void erfundenerOderRecycelterBelegFaelltDurch() {
        Set<String> names = EvidenceVerification.factNames(List.of(FACT_SHEET));

        assertThat(EvidenceVerification.isSupported("FAKT aus_dem_letzten_report: 7", names)).isFalse();
        assertThat(EvidenceVerification.isSupported("TODO 5594f26c", names)).isFalse();
        assertThat(EvidenceVerification.isSupported("", names)).isFalse();
        assertThat(EvidenceVerification.isSupported(null, names)).isFalse();
    }

    /** Without a fact sheet there is nothing to check — no fact sheet is no suspicion. */
    @Test
    void ohneFaktenblattBleibtJedesFindingGedeckt() {
        assertThat(EvidenceVerification.isSupported("was auch immer", Set.of())).isTrue();
        assertThat(EvidenceVerification.isSupported(null, Set.of())).isTrue();
    }

    /**
     * The limit of the mechanical check, recorded on purpose: an existing measured value
     * covers the evidence even if the conclusion has nothing to do with it. That is exactly how the
     * triggering case got through — what is checked is the origin of the evidence, not its load-bearing capacity.
     */
    @Test
    void erkenntKeineFehldeutungEinesEchtenMesswerts() {
        Set<String> names = EvidenceVerification.factNames(List.of(FACT_SHEET));

        assertThat(EvidenceVerification.isSupported(
                "TODO 5594f26c, FAKT non_main_runtime_timeout_lines: 4", names)).isTrue();
    }
}
