package com.automation.service.assistant.fetch;

import com.automation.service.assistant.context.BlockTypesIndexSection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct tests of {@link BlockSpecs}: one spec per block type, in the documented shape, and the
 * branches of {@link BlockSpecs#reference} and {@link BlockSpecs#format}. The key-by-key check
 * against the executor source is {@code BlockSpecConfigKeysGateTest}; the executor scan is
 * {@code BlockTypeListsGuardTest}.
 */
class BlockSpecsTest {

    /** The block types CLAUDE.md lists under "Block types". */
    private static final List<String> DOCUMENTED_TYPES = List.of(
            "simple", "shell", "retry", "conditional", "foreach", "while", "switch", "agentic", "approval",
            "sub-workflow", "skill-capability", "human-input", "integration", "document", "todo-create",
            "todo-update", "todo-close", "compress", "compress-ai", "dashboard-report");

    /** Blocks whose executor resolves provider/model/effort through {@code BlockAiSettings}. */
    private static final Set<String> WITH_AI_OVERRIDES = Set.of(
            "simple", "agentic", "conditional", "foreach", "while", "retry", "skill-capability");

    private static final ObjectMapper JSON = new ObjectMapper();

    static List<String> types() {
        return new TreeSet<>(BlockSpecs.types()).stream().toList();
    }

    @Test
    void everyDocumentedBlockTypeHasASpec_andNoOther() {
        assertThat(BlockSpecs.types()).containsExactlyInAnyOrderElementsOf(DOCUMENTED_TYPES);
    }

    @Test
    void specsCoverTheIndexSection() {
        assertThat(BlockSpecs.types()).containsExactlyInAnyOrderElementsOf(
                BlockTypesIndexSection.BLOCK_TYPES.stream().map(BlockTypesIndexSection.BlockTypeEntry::type).toList());
    }

    @Test
    void typesCannotBeChangedFromOutside() {
        assertThatThrownBy(() -> BlockSpecs.types().add("custom"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest
    @MethodSource("types")
    void spec_hasHeaderRequiredConfigAndOneJsonExample(String type) throws Exception {
        String spec = BlockSpecs.format(type);

        assertThat(spec).startsWith("**Block type: " + type + "** — ").endsWith("\n");
        assertThat(spec.lines().filter(l -> l.startsWith("Required config:"))).hasSize(1);
        List<String> examples = spec.lines().filter(l -> l.startsWith("Example config: ")).toList();
        assertThat(examples).as("exactly one example line").hasSize(1);
        JsonNode example = JSON.readTree(examples.get(0).substring("Example config: ".length()));
        assertThat(example.isObject()).as("example of %s is a JSON object", type).isTrue();
        assertThat(example.size()).isPositive();
    }

    @ParameterizedTest
    @MethodSource("types")
    void aiOverrideLine_onlyForBlocksThatCallAnAi(String type) {
        boolean hasLine = BlockSpecs.format(type).lines().anyMatch(l -> l.startsWith("AI overrides:"));

        assertThat(hasLine).as("AI overrides line in %s", type).isEqualTo(WITH_AI_OVERRIDES.contains(type));
    }

    /**
     * Every key the one-glance summary shows ({@link BlockConfigSummaryFormatter}) must be named in
     * the spec of its type, so the assistant can look up what it sees.
     */
    @Test
    void everyKeyTheSummaryShowsIsNamedInTheSpec() {
        BlockConfigSummaryFormatter.shownKeys().forEach((type, keys) -> {
            assertThat(BlockSpecs.types()).as("summary type %s has a spec", type).contains(type);
            String spec = BlockSpecs.format(type);
            for (String key : keys) {
                assertThat(spec).as("spec of %s names `%s`", type, key).contains("`" + key + "`");
            }
        });
    }

    @Test
    void format_unknownType_listsTheAvailableTypes() {
        String answer = BlockSpecs.format("teleport");

        assertThat(answer).startsWith("Unknown block type 'teleport'. Available: ").endsWith("\n");
        assertThat(answer).contains("shell", "dashboard-report");
    }

    @Test
    void format_isCaseSensitive() {
        assertThat(BlockSpecs.format("Shell")).startsWith("Unknown block type 'Shell'");
    }

    @Test
    void reference_keepsOrder_dropsAiOverrides_blankLineAfterEach() {
        String reference = BlockSpecs.reference(List.of("simple", "shell"));

        assertThat(reference.indexOf("**Block type: simple**"))
                .isLessThan(reference.indexOf("**Block type: shell**"));
        assertThat(reference).doesNotContain("AI overrides:");
        assertThat(reference).contains("\n\n**Block type: shell**").endsWith("\n\n");
        assertThat(reference).contains("Example config: {\"command\": \"List the three riskiest");
    }

    @Test
    void reference_withoutAiOverrides_keepsTheSpecOtherwiseVerbatim() {
        assertThat(BlockSpecs.reference(List.of("shell"))).isEqualTo(BlockSpecs.format("shell") + "\n");
    }

    @Test
    void reference_emptyList_isEmpty() {
        assertThat(BlockSpecs.reference(List.of())).isEmpty();
    }

    @Test
    void reference_unknownType_throws() {
        assertThatThrownBy(() -> BlockSpecs.reference(List.of("shell", "teleport")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No block specification for 'teleport'");
    }
}
