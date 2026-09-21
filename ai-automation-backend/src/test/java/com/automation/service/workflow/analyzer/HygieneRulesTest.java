package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Hygieneregeln direkt: Grenzen der Muster und Schwellwerte. */
class HygieneRulesTest {

    private static final String PARAGRAPH =
            "Arbeite ausschliesslich im Projektverzeichnis und aendere keine Dateien ausserhalb davon, niemals.";

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static List<AnalyzerFinding> apply(String instructions, BlockDefinition... blocks) {
        List<AnalyzerFinding> out = new ArrayList<>();
        HygieneRules.apply(List.of(blocks), instructions, out);
        return out;
    }

    @Test
    void homePfadNenntDenBenutzernamenUndGiltFuerJedenBlocktyp() {
        List<AnalyzerFinding> out = apply(null,
                block("simple", "s", Map.of("prompt", "Lies /Users/anna/notes.md")));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("absolute-home-path");
            assertThat(f.severity()).isEqualTo(Severity.HIGH);
            assertThat(f.detail()).contains("/home/anna");
        });
    }

    @Test
    void homeOhneBenutzerverzeichnisIstKeinBefund() {
        assertThat(apply(null, block("simple", "s", Map.of("prompt", "cd /home und dann $HOME/x"))))
                .isEmpty();
    }

    @Test
    void homePfadWirdJeBlockNurEinmalGemeldet() {
        assertThat(apply(null, block("simple", "s",
                Map.of("prompt", "/home/a/x /home/b/y")))).hasSize(1);
    }

    @Test
    void shellMitSetEOderErrexitIstStrikt() {
        assertThat(apply(null,
                block("shell", "a", Map.of("command", "set -e\nls")),
                block("shell", "b", Map.of("command", "set -o errexit; ls")))).isEmpty();
    }

    @Test
    void shellOhneStrictModeIstLowUndLeererBefehlNicht() {
        List<AnalyzerFinding> out = apply(null,
                block("shell", "a", Map.of("command", "ls")),
                block("shell", "b", Map.of("command", "  ")),
                block("shell", "c", Map.of("command", 42)),
                block("simple", "d", Map.of("command", "kein shell")));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("missing-strict-mode");
            assertThat(f.severity()).isEqualTo(Severity.LOW);
            assertThat(f.blockIndex()).isZero();
        });
    }

    @Test
    void woertlicherAbsatzAbAchtzigZeichenIstDoppelt() {
        String instructions = "Kurz.\n\n" + PARAGRAPH + "\n   \nNoch was.";
        List<AnalyzerFinding> out = apply(instructions,
                block("simple", "s", Map.of("prompt", "Aufgabe.\n" + PARAGRAPH)));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("duplicated-instructions");
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(f.detail()).contains(PARAGRAPH.length() + " Zeichen");
        });
    }

    @Test
    void kurzeAbsaetzeZaehlenNichtAlsDoppelt() {
        String kurz = PARAGRAPH.substring(0, 79);
        assertThat(apply(kurz, block("simple", "s", Map.of("prompt", kurz)))).isEmpty();
    }

    @Test
    void mehrereDoppelteAbsaetzeErgebenEinenBefund() {
        String second = PARAGRAPH.replace("Arbeite", "Schreibe");
        List<AnalyzerFinding> out = apply(PARAGRAPH + "\n\n" + second,
                block("simple", "s", Map.of("prompt", PARAGRAPH + "\n" + second)));

        assertThat(out).extracting(AnalyzerFinding::rule).containsExactly("duplicated-instructions");
    }

    @Test
    void doppelteAnweisungNutztCommandWennPromptFehlt() {
        assertThat(apply(PARAGRAPH, block("simple", "s", Map.of("command", PARAGRAPH))))
                .extracting(AnalyzerFinding::rule).containsExactly("duplicated-instructions");
        assertThat(apply("   ", block("simple", "s", Map.of("command", PARAGRAPH)))).isEmpty();
        assertThat(apply(PARAGRAPH, block("simple", "s", Map.of()))).isEmpty();
    }

    @Test
    void datierteModellIdNurImDirektenModellFeld() {
        List<AnalyzerFinding> out = apply(null,
                block("simple", "a", Map.of("model", "claude-sonnet-4-5")),
                block("simple", "b", Map.of("model", "sonnet")),
                block("simple", "c", Map.of("prompt", "nutze claude-opus-4")));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("dated-model-id");
            assertThat(f.blockName()).isEqualTo("a");
            assertThat(f.detail()).contains("\"claude-sonnet-4-5\"");
        });
    }
}
