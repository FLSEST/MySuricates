package com.automation.service.assistant.fetch;

import com.automation.service.assistant.ExecutorConfigReads;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Direct tests of {@link BlockSpecs}: one spec per block type CLAUDE.md lists, in the documented
 * shape, the "AI overrides:" line exactly where the executor resolves provider/model/effort, and the
 * branches of {@link BlockSpecs#reference} and {@link BlockSpecs#format}. The key-by-key check
 * against the executor source is {@code BlockSpecConfigKeysGateTest}; the executor scan and the
 * index section are {@code BlockTypeListsGuardTest}.
 */
class BlockSpecsTest {

    /** The "Block types" line of CLAUDE.md, the list an agent reads first ({@code - **Block types**: a, b — see …}). */
    private static final Pattern CLAUDE_MD_BLOCK_TYPES = Pattern.compile("(?m)^- \\*\\*Block types\\*\\*: (.+?) — ");

    /**
     * Executors that resolve provider/model/effort ({@link ExecutorConfigReads#resolvesAiSettings})
     * but whose spec names the overrides in its own words instead of the shared "AI overrides:" line,
     * each with the text that must stay in its spec. Every other executor that resolves them needs
     * the line, so a new AI executor without it fails here.
     */
    private static final Map<String, List<String>> OWN_AI_WORDING = Map.of(
            // only "CLAUDE" runs it without tools, so its Optional line names the keys itself
            "compress-ai", List.of("`provider` (only \"CLAUDE\"", "`model`", "`effort`"),
            // the overrides belong to the nested "simple" blocks, not to the switch config
            "switch", List.of("AI overrides for \"simple\""));

    private static final ObjectMapper JSON = new ObjectMapper();

    static List<String> types() {
        return new TreeSet<>(BlockSpecs.types()).stream().toList();
    }

    @Test
    void everyBlockTypeClaudeMdListsHasASpec_andNoOther() throws IOException {
        assertThat(BlockSpecs.types()).containsExactlyInAnyOrderElementsOf(claudeMdBlockTypes());
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

    @Test
    void aiOverrideLine_exactlyWhereTheExecutorResolvesProviderModelEffort() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String type : types()) {
            String spec = BlockSpecs.format(type);
            boolean hasLine = spec.lines().anyMatch(l -> l.startsWith("AI overrides:"));
            boolean resolves = ExecutorConfigReads.resolvesAiSettings(type);
            List<String> ownWording = OWN_AI_WORDING.get(type);
            if (ownWording == null) {
                if (hasLine != resolves) {
                    problems.add(type + (resolves
                            ? ": the executor resolves provider/model/effort, the spec lacks the \"AI overrides:\" line"
                            : ": the spec has the \"AI overrides:\" line, the executor resolves no AI settings"));
                }
                continue;
            }
            if (!resolves) {
                problems.add(type + " is in OWN_AI_WORDING, but its executor resolves no AI settings");
            }
            if (hasLine) {
                problems.add(type + " has its own AI wording and the shared line as well");
            }
            ownWording.stream().filter(text -> !spec.contains(text))
                    .forEach(text -> problems.add(type + " lost its own AI wording: " + text));
        }

        assertThat(problems).isEmpty();
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

    /** The block types of the "Block types" line in CLAUDE.md at the repository root. */
    private static List<String> claudeMdBlockTypes() throws IOException {
        Path claudeMd = Files.isRegularFile(Paths.get("../CLAUDE.md"))
                ? Paths.get("../CLAUDE.md") : Paths.get("CLAUDE.md");
        assertThat(claudeMd).as("CLAUDE.md nicht gefunden ab " + Paths.get("").toAbsolutePath()).isRegularFile();
        Matcher line = CLAUDE_MD_BLOCK_TYPES.matcher(Files.readString(claudeMd));
        assertThat(line.find()).as("line \"- **Block types**: simple, shell, … — see …\" in " + claudeMd).isTrue();
        List<String> types = Arrays.stream(line.group(1).split(",")).map(String::strip).toList();
        assertThat(types).as("block types in CLAUDE.md").isNotEmpty().allMatch(t -> t.matches("[a-z]+(-[a-z]+)*"));
        return types;
    }

    @Test
    void reference_unknownType_throws() {
        assertThatThrownBy(() -> BlockSpecs.reference(List.of("shell", "teleport")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No block specification for 'teleport'");
    }
}
