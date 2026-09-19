package com.automation.service.audit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Ein Finding muss sich auf eine Messung dieses Laufs berufen (TODO a94b56a0). */
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

    /** Ohne Faktenblatt gibt es nichts zu pruefen — kein Faktenblatt ist kein Verdacht. */
    @Test
    void ohneFaktenblattBleibtJedesFindingGedeckt() {
        assertThat(EvidenceVerification.isSupported("was auch immer", Set.of())).isTrue();
        assertThat(EvidenceVerification.isSupported(null, Set.of())).isTrue();
    }

    /**
     * Die Grenze der mechanischen Pruefung, absichtlich festgehalten: Ein vorhandener Messwert
     * deckt den Beleg, auch wenn die Schlussfolgerung mit ihm nichts zu tun hat. Genau so kam der
     * Anlassfall durch — geprueft wird die Herkunft des Belegs, nicht seine Tragfaehigkeit.
     */
    @Test
    void erkenntKeineFehldeutungEinesEchtenMesswerts() {
        Set<String> names = EvidenceVerification.factNames(List.of(FACT_SHEET));

        assertThat(EvidenceVerification.isSupported(
                "TODO 5594f26c, FAKT non_main_runtime_timeout_lines: 4", names)).isTrue();
    }
}
