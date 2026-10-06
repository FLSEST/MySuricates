package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.service.workflow.analyzer.AnalyzerFinding.Severity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Hygiene rules directly: limits of the patterns and thresholds. */
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
                block("simple", "s", Map.of("command", "Lies /Users/anna/notes.md")));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("absolute-home-path");
            assertThat(f.severity()).isEqualTo(Severity.HIGH);
            assertThat(f.detail()).contains("/home/anna");
        });
    }

    @Test
    void homeOhneBenutzerverzeichnisIstKeinBefund() {
        assertThat(apply(null, block("simple", "s", Map.of("command", "cd /home und dann $HOME/x"))))
                .isEmpty();
    }

    @Test
    void homePfadWirdJeBlockNurEinmalGemeldet() {
        assertThat(apply(null, block("simple", "s",
                Map.of("command", "/home/a/x /home/b/y")))).hasSize(1);
    }

    /** cccb0301: the container side of a mount is a path inside the image, the same on every machine. */
    @Test
    void mountTargetInsideTheImageIsNoHomePath() {
        assertThat(apply(null, block("shell", "trivy", Map.of(
                "container_args", List.of("trivy", "fs", "/workspace"),
                "mounts", List.of("{{ home_dir }}/.m2/repository:/home/agent/.m2/repository:ro")))))
                .noneMatch(f -> f.rule().equals("absolute-home-path"));
    }

    @Test
    void mountSourceUnderAHomeDirectoryIsStillReported() {
        assertThat(apply(null, block("shell", "s", Map.of(
                "container_args", List.of("tool"),
                "mounts", List.of("/home/anna/data:/data:ro")))))
                .singleElement().satisfies(f -> {
                    assertThat(f.rule()).isEqualTo("absolute-home-path");
                    assertThat(f.detail()).contains("/home/anna");
                });
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
                block("simple", "s", Map.of("command", "Aufgabe.\n" + PARAGRAPH)));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("duplicated-instructions");
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(f.detail()).contains(PARAGRAPH.length() + " Zeichen");
        });
    }

    @Test
    void kurzeAbsaetzeZaehlenNichtAlsDoppelt() {
        String kurz = PARAGRAPH.substring(0, 79);
        assertThat(apply(kurz, block("simple", "s", Map.of("command", kurz)))).isEmpty();
    }

    @Test
    void mehrereDoppelteAbsaetzeErgebenEinenBefund() {
        String second = PARAGRAPH.replace("Arbeite", "Schreibe");
        List<AnalyzerFinding> out = apply(PARAGRAPH + "\n\n" + second,
                block("simple", "s", Map.of("command", PARAGRAPH + "\n" + second)));

        assertThat(out).extracting(AnalyzerFinding::rule).containsExactly("duplicated-instructions");
    }

    @Test
    void ohneInstructionsOderOhneAuftragKeinDoppelterAuftrag() {
        assertThat(apply(PARAGRAPH, block("simple", "s", Map.of("command", PARAGRAPH))))
                .extracting(AnalyzerFinding::rule).containsExactly("duplicated-instructions");
        assertThat(apply("   ", block("simple", "s", Map.of("command", PARAGRAPH)))).isEmpty();
        assertThat(apply(PARAGRAPH, block("simple", "s", Map.of()))).isEmpty();
    }

    /** TODO c52eba8f: agentic carries its task in goal — the rule did not see it. */
    @Test
    void agenticZielMitWoertlichemAbsatzIstDoppelt() {
        assertThat(apply(PARAGRAPH, block("agentic", "plan", Map.of("goal", "Plane.\n" + PARAGRAPH))))
                .extracting(AnalyzerFinding::rule).containsExactly("duplicated-instructions");
    }

    /** Wrapper schicken ihren Befehl nur im KI-Modus mit workflow.instructions an die KI; skill-capability ihren input. */
    @Test
    void kiAuftraegeDerWrapperUndDerCapabilityZaehlenNurImKiModus() {
        List<AnalyzerFinding> out = apply(PARAGRAPH,
                block("retry", "a", Map.of("command", PARAGRAPH)),
                block("retry", "b", Map.of("command", PARAGRAPH, "execution_mode", "shell")),
                block("conditional", "c", Map.of("condition", "x", "else_command", PARAGRAPH)),
                block("conditional", "d", Map.of("condition", "true", "then_command", PARAGRAPH,
                        "execution_mode", "shell")),
                block("skill-capability", "e", Map.of("capability", "analyze", "input", PARAGRAPH)));

        assertThat(out).extracting(AnalyzerFinding::blockName).containsExactly("a", "c", "e");
    }

    /** A shell command, the question to a human and a key that no executor reads do not go to the AI. */
    @Test
    void shellBefehlFrageAnMenschenUndUngelesenerSchluesselSindKeinDoppelterAuftrag() {
        assertThat(apply(PARAGRAPH,
                block("shell", "a", Map.of("command", "set -e\necho '" + PARAGRAPH + "'")),
                block("human-input", "b", Map.of("prompt", PARAGRAPH)),
                block("simple", "c", Map.of("prompt", PARAGRAPH, "command", "Fasse zusammen")))).isEmpty();
    }

    /**
     * TODO ce4abac1: a simple block in a switch case gets workflow.instructions just the same as
     * system prompt — the rule only read the direct values of the switch block.
     */
    @Test
    void kiAuftragEinesVerschachteltenBlocksIstDoppeltUndNenntIhn() {
        Map<String, Object> config = Map.of("expression", "{{ environment }}",
                "cases", List.of(Map.of("value", "prod", "blocks", List.of(
                        Map.of("type", "shell", "config", Map.of("command", "set -e\necho deploy")),
                        Map.of("type", "simple", "name", "notes",
                                "config", Map.of("command", "Schreibe Release-Notes.\n" + PARAGRAPH))))));

        assertThat(apply(PARAGRAPH, block("switch", "route", config))).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("duplicated-instructions");
            assertThat(f.blockName()).isEqualTo("route");
            assertThat(f.detail()).contains("verschachtelten Block 'notes'");
        });
    }

    /** The same applies nested as above: shell does not go to the AI — also simple with execution_mode "shell". */
    @Test
    void verschachtelteShellBefehleSindKeinDoppelterAuftrag() {
        Map<String, Object> config = Map.of("expression", "x",
                "cases", List.of(Map.of("value", "x", "blocks", List.of(
                        Map.of("type", "shell", "config", Map.of("command", "set -e\necho '" + PARAGRAPH + "'"))))),
                "default", List.of(Map.of("type", "simple",
                        "config", Map.of("command", PARAGRAPH, "execution_mode", "shell"))));

        assertThat(apply(PARAGRAPH, block("switch", "route", config))).isEmpty();
    }

    @Test
    void datierteModellIdNurImDirektenModellFeld() {
        List<AnalyzerFinding> out = apply(null,
                block("simple", "a", Map.of("model", "claude-sonnet-4-5")),
                block("simple", "b", Map.of("model", "sonnet")),
                block("simple", "c", Map.of("command", "nutze claude-opus-4")));

        assertThat(out).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("dated-model-id");
            assertThat(f.blockName()).isEqualTo("a");
            assertThat(f.detail()).contains("\"claude-sonnet-4-5\"");
        });
    }
}
