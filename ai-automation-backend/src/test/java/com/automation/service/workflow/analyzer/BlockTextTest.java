package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import com.automation.service.assistant.fetch.BlockSpecs;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BlockTextTest {

    private static BlockDefinition block(Map<String, Object> config) {
        return block("simple", config);
    }

    private static BlockDefinition block(String type, Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType(type);
        b.setConfig(config);
        return b;
    }

    @Test
    void nullBlockUndNullConfigSindLeer() {
        assertThat(BlockText.allStrings(null)).isEmpty();
        assertThat(BlockText.joined(block(null))).isEmpty();
        assertThat(BlockText.directValue(null, "x")).isNull();
        assertThat(BlockText.directValue(block(null), "x")).isNull();
        assertThat(BlockText.instructions(null)).isEmpty();
        assertThat(BlockText.instructions(block(null))).isEmpty();
        assertThat(BlockText.instructions(block(null, Map.of("command", "x")))).isEmpty();
    }

    @Test
    void sammeltStringsAusMapsListenUndSetsInReihenfolge() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("command", "eins");
        config.put("timeout_seconds", 30);
        config.put("cases", List.of(Map.of("blocks", List.of(Map.of("command", "zwei")))));
        config.put("tags", Set.of("drei"));

        assertThat(BlockText.allStrings(block(config))).containsExactly("eins", "zwei", "drei");
        assertThat(BlockText.joined(block(config))).isEqualTo("eins\nzwei\ndrei");
    }

    @Test
    void tiefeUeberZwoelfEbenenWirdAbgeschnitten() {
        Object erlaubt = "ok";
        for (int i = 0; i < 11; i++) {
            erlaubt = List.of(erlaubt);
        }
        Object zuTief = "weg";
        for (int i = 0; i < 12; i++) {
            zuTief = List.of(zuTief);
        }
        // + 1 Ebene für die Config-Map selbst
        assertThat(BlockText.allStrings(block(Map.of("a", erlaubt)))).containsExactly("ok");
        assertThat(BlockText.allStrings(block(Map.of("a", zuTief)))).isEmpty();
    }

    @Test
    void zyklischeConfigTerminiert() {
        List<Object> self = new ArrayList<>();
        self.add("x");
        self.add(self);
        assertThat(BlockText.allStrings(block(Map.of("loop", self)))).isNotEmpty().allMatch("x"::equals);
    }

    @Test
    void directValueNurFuerStrings() {
        BlockDefinition b = block(Map.of("command", "ls", "timeout_seconds", 10));
        assertThat(BlockText.directValue(b, "command")).isEqualTo("ls");
        assertThat(BlockText.directValue(b, "timeout_seconds")).isNull();
        assertThat(BlockText.directValue(b, "fehlt")).isNull();
    }

    /** TODO c52eba8f: je Typ der Schlüssel, den der Executor liest — nicht prompt vor command, bei agentic goal. */
    @Test
    void instructionsLesenJeTypDenSchluesselDesExecutors() {
        assertThat(BlockText.instructions(block(Map.of("prompt", "p", "command", "c")))).containsExactly("c");
        assertThat(BlockText.instructions(block(Map.of("command", 1)))).isEmpty();
        assertThat(BlockText.instructions(block("agentic", Map.of("goal", "g", "success_criteria", "s"))))
                .containsExactly("g");
        assertThat(BlockText.instructions(block("skill-capability", Map.of("capability", "analyze", "input", "i"))))
                .containsExactly("i");
        assertThat(BlockText.instructions(block("conditional",
                Map.of("condition", "x", "then_command", "t", "else_command", "e")))).containsExactly("t", "e");
        assertThat(BlockText.instructions(block("foreach", Map.of("command", "Fasse {{ item }} zusammen",
                "execution_mode", "ai")))).containsExactly("Fasse {{ item }} zusammen");
    }

    /** Was als Shell läuft oder einen Menschen fragt, geht nicht mit workflow.instructions an die KI. */
    @Test
    void shellBefehleUndFragenAnMenschenSindKeinKiAuftrag() {
        assertThat(BlockText.instructions(block("shell", Map.of("command", "ls")))).isEmpty();
        assertThat(BlockText.instructions(block("foreach", Map.of("command", "wc -l {{ item }}",
                "execution_mode", "shell")))).isEmpty();
        assertThat(BlockText.instructions(block("human-input", Map.of("prompt", "Welche Sprache?")))).isEmpty();
        assertThat(BlockText.instructions(block("approval", Map.of("question", "Deploy?")))).isEmpty();
        assertThat(BlockText.instructions(block("compress-ai", Map.of("source", "previous",
                "instruction", "Kuerze")))).isEmpty();
    }

    /**
     * TODO ce4abac1: die Aufträge verschachtelter Blöcke, über denselben Walker wie die Typ- und
     * execution_mode-Prüfung (switch-Cases, default, eine blocks-Liste) — je Blockname, sonst Typ.
     */
    @Test
    void nestedInstructionsLesenVerschachtelteBloeckeJeTyp() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("expression", "{{ env }}");
        config.put("cases", List.of(
                Map.of("value", "a", "blocks", List.of(
                        Map.of("type", "simple", "name", "notes", "config", Map.of("command", "Notizen")),
                        Map.of("type", "shell", "config", Map.of("command", "ls")))),
                Map.of("value", "b", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "als Shell", "execution_mode", "shell"))))));
        config.put("default", List.of(Map.of("type", "simple", "config", Map.of("command", "Standard"))));

        assertThat(BlockText.nestedInstructions(block("switch", config)))
                .containsExactly(Map.entry("notes", List.of("Notizen")), Map.entry("simple", List.of("Standard")));
        assertThat(BlockText.instructions(block("switch", config))).isEmpty();
        assertThat(BlockText.nestedInstructions(null)).isEmpty();
        assertThat(BlockText.nestedInstructions(block("simple", Map.of("command", "x")))).isEmpty();
    }

    /** Die Schlüssel stehen in der Spezifikation ihres Typs; die hält BlockSpecConfigKeysGateTest am Executor. */
    @Test
    void promptSchluesselSindDieDerBlockSpezifikation() {
        BlockText.PROMPT_KEYS.forEach((type, keys) -> {
            String spec = BlockSpecs.reference(List.of(type));
            keys.forEach(key -> assertThat(spec).as("BlockSpecs für %s", type).contains("`" + key + "`"));
        });
    }
}
