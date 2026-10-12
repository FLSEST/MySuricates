package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.modules.ModuleRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code actor-module-disabled} (TODO dca0c03e): an AI actor on an instance whose module ai-actors is off. */
class ActorRulesTest {

    private final WorkflowAnalyzer moduleOff = new WorkflowAnalyzer(ModuleRegistry.of(Map.of(), Map.of()));
    private final WorkflowAnalyzer moduleOn = new WorkflowAnalyzer(ModuleRegistry.of(Map.of("ai-actors", "true"), Map.of()));

    private static BlockDefinition block(String type, String actor) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setName("entscheiden");
        Map<String, Object> config = new HashMap<>(Map.of("question", "Weiter?", "prompt", "Wert?"));
        if (actor != null) config.put("actor", actor);
        b.setConfig(config);
        return b;
    }

    private static List<AnalyzerFinding> findings(WorkflowAnalyzer analyzer, BlockDefinition... blocks) {
        return analyzer.analyze(List.of(blocks), null).stream().filter(f -> f.rule().equals("actor-module-disabled")).toList();
    }

    @Test
    @DisplayName("a literal ai or shadow actor with the module off is a HIGH finding")
    void literalActorWithModuleOff() {
        var findings = findings(moduleOff, block("approval", "ai"), block("human-input", "shadow"));

        assertThat(findings).hasSize(2).allSatisfy(f -> {
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.HIGH);
            assertThat(f.detailEn()).contains("module ai-actors is off", "automation.modules.ai-actors=true");
            assertThat(f.detail()).contains("Modul ai-actors ist aus");
        });
        assertThat(findings.get(0).blockIndex()).isZero();
        assertThat(findings.get(1).blockIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("a placeholder actor with the module off is MEDIUM — the run variable decides")
    void placeholderActorWithModuleOff() {
        var findings = findings(moduleOff, block("approval", "{{ actor_po }}"));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(AnalyzerFinding.Severity.MEDIUM);
            assertThat(f.detailEn()).contains("{{ actor_po }}");
        });
    }

    @Test
    @DisplayName("human, no actor, other block types and the module on report nothing")
    void nothingOtherwise() {
        assertThat(findings(moduleOff, block("approval", "human"), block("human-input", null),
                block("simple", "ai"))).isEmpty();
        assertThat(findings(moduleOn, block("approval", "ai"), block("human-input", "{{ actor_sm }}"))).isEmpty();
    }

    @Test
    @DisplayName("a nested approval in a switch case is reported on the outer block")
    void nestedActor() {
        BlockDefinition outer = new BlockDefinition();
        outer.setType("switch");
        outer.setName("verteilen");
        outer.setConfig(new HashMap<>(Map.of("expression", "{{ x }}", "cases", List.of(Map.of("value", "a",
                "blocks", List.of(Map.of("type", "approval", "name", "freigeben", "config", Map.of("actor", "ai"))))))));

        var findings = findings(moduleOff, outer);

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.blockIndex()).isZero();
            assertThat(f.blockName()).isEqualTo("verteilen › freigeben");
        });
    }
}
