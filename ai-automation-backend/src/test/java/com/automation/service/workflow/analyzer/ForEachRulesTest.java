package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ForEachRulesTest {

    private static BlockDefinition block(String type, String name, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName(name);
        b.setConfig(config);
        return b;
    }

    private static List<AnalyzerFinding> run(BlockDefinition... blocks) {
        List<AnalyzerFinding> out = new ArrayList<>();
        ForEachRules.apply(List.of(blocks), out);
        return out;
    }

    private static Map<String, Object> config(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void befehlOhneQuelleUndOhneItemsIstHigh() {
        var findings = run(block("foreach", "schleife", config("items_command", "ls")));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.rule()).isEqualTo("items-command-without-source");
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.HIGH);
            assertThat(f.blockName()).isEqualTo("schleife");
            assertThat(f.detail()).contains("nicht gesetzt").contains("null Items");
        });
    }

    @Test
    void befehlMitAndererQuelleNenntDieQuelleImText() {
        var findings = run(block("foreach", "f", config("items_command", "ls", "items_source", "static")));

        assertThat(findings).singleElement()
                .satisfies(f -> assertThat(f.detail()).contains("\"static\""));
    }

    @Test
    void springtItemsEinIstEsNurMedium() {
        var findings = run(block("foreach", "f", config("items_command", "ls", "items", List.of("a", "b"))));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.MEDIUM);
            assertThat(f.detail()).contains("kommen aus items");
        });
    }

    @Test
    void itemsAlsNichtLeererTextZaehltAlsItems() {
        var findings = run(block("foreach", "f", config("items_command", "ls", "items", "a\nb")));

        assertThat(findings).singleElement()
                .satisfies(f -> assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.MEDIUM));
    }

    @Test
    void leereItemsZaehlenNichtAlsItems() {
        for (Object empty : new Object[]{List.of(), List.of(" ", ""), "  ", 5}) {
            var findings = run(block("foreach", "f", config("items_command", "ls", "items", empty)));
            assertThat(findings).singleElement()
                    .satisfies(f -> assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.HIGH));
        }
    }

    @Test
    void quelleCommandIstKeinBefund() {
        assertThat(run(block("foreach", "f", config("items_command", "ls", "items_source", "command")))).isEmpty();
    }

    @Test
    void leererOderFehlenderBefehlIstKeinBefund() {
        assertThat(run(block("foreach", "f", config("items_command", "  ")))).isEmpty();
        assertThat(run(block("foreach", "f", config("items", List.of("a"))))).isEmpty();
        assertThat(run(block("foreach", "f", config("items_command", 42)))).isEmpty();
    }

    @Test
    void andereBlocktypenUndBloeckeOhneConfigWerdenIgnoriert() {
        assertThat(run(block("shell", "s", config("items_command", "ls")))).isEmpty();
        assertThat(run(block("foreach", "f", null))).isEmpty();
    }

    @Test
    void indexIstDerPositionImWorkflow() {
        var findings = run(block("shell", "a", config("command", "x")),
                block("foreach", "b", config("items_command", "ls")));

        assertThat(findings).singleElement().satisfies(f -> assertThat(f.blockIndex()).isEqualTo(1));
    }
}
