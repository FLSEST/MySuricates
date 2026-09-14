package com.automation.service.workflow;

import com.automation.dto.WorkflowSnapshot;
import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDefinitionHasherTest {

    private final WorkflowDefinitionHasher hasher = new WorkflowDefinitionHasher();

    private static BlockDefinition block(String type, Map<String, Object> config) {
        return BlockDefinition.builder().type(type).config(new LinkedHashMap<>(config)).build();
    }

    private static WorkflowSnapshot snapshot(List<BlockDefinition> blocks, Map<String, Object> metadata,
                                             List<String> skillIds) {
        return new WorkflowSnapshot(blocks, "Be brief", "opus", "CLAUDE", metadata, Map.of("env", "test"), skillIds, "high");
    }

    @Test
    void canonicalFormIsPinned() throws Exception {
        WorkflowSnapshot s = snapshot(
                List.of(block("shell", Map.of("command", "echo hi"))),
                Map.of("audit", Map.of("enabled", true)),
                List.of("s2", "s1"));

        String json = hasher.canonicalJson(s);

        // Changing this string changes every stored hash — only do it together with a migration plan.
        assertThat(json).isEqualTo("{\"activatedSkillIds\":[\"s1\",\"s2\"],"
                + "\"blocks\":[{\"config\":{\"command\":\"echo hi\"},\"type\":\"shell\"}],"
                + "\"defaultContext\":{\"env\":\"test\"},\"effort\":\"high\",\"instructions\":\"Be brief\","
                + "\"metadata\":{\"audit\":{\"enabled\":true}},\"model\":\"opus\",\"provider\":\"CLAUDE\"}");
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        assertThat(hasher.hash(s)).isEqualTo(expected).hasSize(64);
    }

    @Test
    void mapKeyOrderDoesNotMatter() {
        Map<String, Object> ab = new LinkedHashMap<>();
        ab.put("a", 1);
        ab.put("b", Map.of("y", 2, "x", 1));
        Map<String, Object> ba = new LinkedHashMap<>();
        ba.put("b", Map.of("x", 1, "y", 2));
        ba.put("a", 1);

        assertThat(hasher.hash(snapshot(List.of(block("simple", ab)), Map.of(), List.of())))
                .isEqualTo(hasher.hash(snapshot(List.of(block("simple", ba)), Map.of(), List.of())));
    }

    @Test
    void blockOrderMatters() {
        BlockDefinition first = block("shell", Map.of("command", "1"));
        BlockDefinition second = block("shell", Map.of("command", "2"));

        assertThat(hasher.hash(snapshot(List.of(first, second), Map.of(), List.of())))
                .isNotEqualTo(hasher.hash(snapshot(List.of(second, first), Map.of(), List.of())));
    }

    @Test
    void skillOrderDoesNotMatter() {
        assertThat(hasher.hash(snapshot(List.of(), Map.of(), List.of("a", "b"))))
                .isEqualTo(hasher.hash(snapshot(List.of(), Map.of(), List.of("b", "a"))));
    }

    @Test
    void nullAndEmptyAreTheSameDefinition() {
        BlockDefinition withEmptyLists = BlockDefinition.builder().type("simple")
                .config(new LinkedHashMap<>(Map.of("prompt", "x"))).skillIds(new ArrayList<>()).requiredCapabilities(new ArrayList<>()).build();
        BlockDefinition withNullLists = BlockDefinition.builder().type("simple")
                .config(new LinkedHashMap<>(Map.of("prompt", "x"))).skillIds(null).requiredCapabilities(null).build();

        WorkflowSnapshot a = new WorkflowSnapshot(List.of(withEmptyLists), "", null, null, Map.of(), Map.of(), List.of(), null);
        WorkflowSnapshot b = new WorkflowSnapshot(List.of(withNullLists), null, null, null, null, null, null, null);

        assertThat(hasher.hash(a)).isEqualTo(hasher.hash(b));
    }

    @Test
    void anyExecutableChangeChangesTheHash() {
        WorkflowSnapshot base = snapshot(List.of(block("shell", Map.of("command", "echo"))), Map.of(), List.of());
        String hash = hasher.hash(base);

        assertThat(hasher.hash(new WorkflowSnapshot(base.blocks(), "other", base.model(), base.provider(),
                base.metadata(), base.defaultContext(), base.activatedSkillIds(), base.effort()))).isNotEqualTo(hash);
        assertThat(hasher.hash(new WorkflowSnapshot(base.blocks(), base.instructions(), "sonnet", base.provider(),
                base.metadata(), base.defaultContext(), base.activatedSkillIds(), base.effort()))).isNotEqualTo(hash);
        assertThat(hasher.hash(snapshot(List.of(block("shell", Map.of("command", "rm -rf /"))), Map.of(), List.of())))
                .isNotEqualTo(hash);
    }
}
