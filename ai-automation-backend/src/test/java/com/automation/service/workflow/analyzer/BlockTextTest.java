package com.automation.service.workflow.analyzer;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BlockTextTest {

    private static BlockDefinition block(Map<String, Object> config) {
        BlockDefinition b = new BlockDefinition();
        b.setType("simple");
        b.setConfig(config);
        return b;
    }

    @Test
    void nullBlockUndNullConfigSindLeer() {
        assertThat(BlockText.allStrings(null)).isEmpty();
        assertThat(BlockText.joined(block(null))).isEmpty();
        assertThat(BlockText.directValue(null, "x")).isNull();
        assertThat(BlockText.directValue(block(null), "x")).isNull();
        assertThat(BlockText.instruction(block(null))).isNull();
    }

    @Test
    void sammeltStringsAusMapsListenUndSetsInReihenfolge() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("prompt", "eins");
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

    @Test
    void instructionBevorzugtPromptVorCommand() {
        assertThat(BlockText.instruction(block(Map.of("prompt", "p", "command", "c")))).isEqualTo("p");
        assertThat(BlockText.instruction(block(Map.of("command", "c")))).isEqualTo("c");
        assertThat(BlockText.instruction(block(Map.of("prompt", 1, "command", "c")))).isEqualTo("c");
    }
}
