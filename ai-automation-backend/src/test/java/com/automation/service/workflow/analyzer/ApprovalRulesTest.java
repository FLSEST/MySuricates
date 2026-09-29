package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalRulesTest {

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static List<AnalyzerFinding> run(BlockDefinition... blocks) {
        List<AnalyzerFinding> out = new ArrayList<>();
        ApprovalRules.apply(List.of(blocks), out);
        return out;
    }

    @Test
    void onNoFailMitStandardOptionenIstKeinBefund() {
        // Standardoptionen enthalten "No", das als Ablehnung gilt
        assertThat(run(block("approval", "freigabe", Map.of("on_no", "fail")))).isEmpty();
    }

    @Test
    void eigeneOptionenOhneAblehnungSindEinHighBefund() {
        var findings = run(block("approval", "freigabe",
                Map.of("on_no", "fail", "options", List.of("Ja", "Vielleicht"))));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("on-no-without-reject-option");
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.HIGH);
            assertThat(f.detail()).contains("on_no: fail").contains("Vielleicht");
        });
    }

    @Test
    void onNoSkipWirdGenausoGeprueft() {
        assertThat(run(block("approval", "a", Map.of("on_no", "skip", "options", List.of("Ok")))))
                .hasSize(1);
    }

    @Test
    void andererOnNoWertOderFehlenderWertIstKeinBefund() {
        assertThat(run(block("approval", "a", Map.of("on_no", "continue", "options", List.of("Ok"))))).isEmpty();
        assertThat(run(block("approval", "a", Map.of("options", List.of("Ok"))))).isEmpty();
    }

    @Test
    void nameDerOptionenVergleichtOhneGrossKleinUndRand() {
        assertThat(run(block("approval", "a", Map.of("on_no", "fail", "options", List.of("Ja", " NEIN "))))).isEmpty();
    }

    @Test
    void rejectOptionsMachenEigeneOptionAlsAblehnungGueltig() {
        assertThat(run(block("approval", "a", Map.of("on_no", "fail",
                "options", List.of("Go", "Stop"), "reject_options", List.of("stop"))))).isEmpty();
    }

    @Test
    void rejectOptionsOhnePassendeOptionSindEinBefundMitDenAblehnungenImText() {
        var findings = run(block("approval", "a", Map.of("on_no", "fail",
                "options", List.of("Go", "Nein"), "reject_options", List.of("Stop"))));

        assertThat(findings).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("Stop"));
    }

    @Test
    void ablehnendeAutoAnswerMachtOnNoWirksam() {
        assertThat(run(block("approval", "a", Map.of("on_no", "fail",
                "options", List.of("Ja"), "auto_answer", "No")))).isEmpty();
    }

    @Test
    void nichtAblehnendeAutoAnswerBleibtBefund() {
        assertThat(run(block("approval", "a", Map.of("on_no", "fail",
                "options", List.of("Ja"), "auto_answer", "Ja")))).hasSize(1);
    }

    @Test
    void nichtStringOptionenWerdenIgnoriert() {
        assertThat(run(block("approval", "a", Map.of("on_no", "fail", "options", List.of(1, 2))))).hasSize(1);
    }

    @Test
    void blockOhneConfigWirdUebersprungen() {
        assertThat(run(block("approval", "a", null))).isEmpty();
    }

    @Test
    void verschachtelterApprovalWirdAmAeusserenBlockMitIndexUndNameGemeldet() {
        var inner = Map.of("type", "approval", "name", "innen",
                "config", Map.of("on_no", "skip", "options", List.of("Ja")));
        var findings = run(
                block("shell", "vorher", Map.of("command", "echo")),
                block("foreach", "schleife", Map.of("blocks", List.of(inner))));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.blockIndex()).isEqualTo(1);
            assertThat(f.blockName()).isEqualTo("schleife › innen");
        });
    }

    @Test
    void verschachtelterApprovalOhneNamenNutztAeusserenNamen() {
        var inner = Map.of("type", "approval",
                "config", Map.of("on_no", "fail", "options", List.of("Ja")));
        var findings = run(block("while", "w", Map.of("body", inner)));

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.blockName()).isEqualTo("w"));
    }

    @Test
    void tiefVerschachteltUeberDieMaximaleTiefeHinausWirdNichtGemeldet() {
        Object nested = Map.of("type", "approval",
                "config", Map.of("on_no", "fail", "options", List.of("Ja")));
        for (int i = 0; i < 20; i++) nested = Map.of("child", nested);

        assertThat(run(block("foreach", "f", Map.of("blocks", nested)))).isEmpty();
    }
}
