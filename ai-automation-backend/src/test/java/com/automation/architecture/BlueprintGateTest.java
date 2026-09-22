package com.automation.architecture;

import com.automation.util.LocalizedJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate for the shipped blueprints (TODO 268ae0db): empty scaffolds with runnable placeholders that show
 * how block types connect.
 *
 * <p>A blueprint promises to run on a fresh install without cost. This test makes that checkable:
 * outside the {@code AI} category, no block calls a model. That means no AI block types, every wrapper
 * that runs a command carries {@code execution_mode: shell} (without it, foreach/while/conditional/retry
 * would ask the AI), and switch cases contain only shell blocks. Each blueprint is also bilingual and
 * sits in one of the three gallery groups.
 */
class BlueprintGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> CATEGORIES = Set.of("BASICS", "COMBINATIONS", "AI");
    private static final Set<String> AI_TYPES = Set.of("simple", "agentic", "compress-ai", "skill-capability");
    private static final Set<String> COMMAND_WRAPPERS = Set.of("foreach", "while", "conditional", "retry");

    @Test
    void blueprintsAreShipped_bilingual_andGrouped() throws IOException {
        List<Path> files = blueprintFiles();
        assertThat(files).as("templates/blueprints/*.json").hasSizeGreaterThanOrEqualTo(10);

        List<String> problems = new ArrayList<>();
        List<Integer> sortOrders = new ArrayList<>();
        for (Path file : files) {
            JsonNode root = MAPPER.readTree(file.toFile());
            String label = file.getFileName().toString();
            if (!root.path("id").asText().startsWith("blueprint-")) {
                problems.add(label + ": id beginnt nicht mit blueprint-");
            }
            if (!label.equals(root.path("id").asText() + ".json")) {
                problems.add(label + ": Dateiname ≠ id");
            }
            if (!LocalizedJson.declaredLocales(root).containsAll(List.of("de", "en"))) {
                problems.add(label + ": ohne \"i18n\": [\"de\", \"en\"]");
            }
            for (String field : List.of("name", "description")) {
                for (String locale : List.of("de", "en")) {
                    if (root.path(field).path(locale).asText("").isBlank()) {
                        problems.add(label + ": " + field + "." + locale + " fehlt");
                    }
                }
            }
            if (!CATEGORIES.contains(root.path("category").asText())) {
                problems.add(label + ": Kategorie '" + root.path("category").asText() + "' nicht in " + CATEGORIES);
            }
            sortOrders.add(root.path("sortOrder").asInt(-1));
        }
        assertThat(problems).isEmpty();
        assertThat(sortOrders.stream().sorted().toList())
                .as("sortOrder der Blaupausen fortlaufend ab 0")
                .isEqualTo(java.util.stream.IntStream.range(0, files.size()).boxed().toList());
    }

    @Test
    void blueprintsOutsideTheAiGroup_neverCallAModel() throws IOException {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Path file : blueprintFiles()) {
            JsonNode root = MAPPER.readTree(file.toFile());
            if ("AI".equals(root.path("category").asText())) {
                continue;
            }
            checked++;
            problems.addAll(modelCalls(root, file.getFileName().toString()));
        }
        assertThat(checked).isPositive();
        assertThat(problems).as("Blaupausen ohne KI-Kategorie versprechen 0 Tokens").isEmpty();
    }

    @Test
    void theRuleCatchesEveryWayToReachTheAi() throws IOException {
        JsonNode bad = MAPPER.readTree("""
                {"category": "BASICS", "blocks": [
                  {"type": "simple", "config": {"command": "x"}},
                  {"type": "foreach", "config": {"items": ["a"], "command": "echo {{ item }}"}},
                  {"type": "switch", "config": {"expression": "a", "cases": [
                     {"value": "a", "blocks": [{"type": "simple", "config": {"command": "y"}}]}]}}]}""");

        List<String> problems = modelCalls(bad, "bad.json");

        assertThat(problems).anyMatch(p -> p.contains("simple"))
                .anyMatch(p -> p.contains("foreach") && p.contains("execution_mode"))
                .anyMatch(p -> p.contains("switch"));
    }

    static List<String> modelCalls(JsonNode root, String label) {
        List<String> problems = new ArrayList<>();
        walkBlocks(root.path("blocks"), label, (path, block) -> {
            String type = block.path("type").asText();
            if (AI_TYPES.contains(type)) {
                problems.add(path + ": Block-Typ " + type + " ruft ein Modell auf");
            }
            if (COMMAND_WRAPPERS.contains(type) && !"shell".equals(block.path("config").path("execution_mode").asText())) {
                problems.add(path + ": " + type + " ohne execution_mode: shell fragt die KI");
            }
        });
        return problems;
    }

    /** Top-level blocks plus switch cases/default, the only places a block contains blocks. */
    private static void walkBlocks(JsonNode blocks, String path, BiConsumer<String, JsonNode> visitor) {
        if (!blocks.isArray()) {
            return;
        }
        for (int i = 0; i < blocks.size(); i++) {
            JsonNode block = blocks.get(i);
            String here = path + "[" + i + "]";
            visitor.accept(here, block);
            if ("switch".equals(block.path("type").asText())) {
                JsonNode cases = block.path("config").path("cases");
                for (int c = 0; c < cases.size(); c++) {
                    walkBlocks(cases.get(c).path("blocks"), here + ".switch.cases[" + c + "]", visitor);
                }
                walkBlocks(block.path("config").path("default"), here + ".switch.default", visitor);
            }
        }
    }

    private static List<Path> blueprintFiles() throws IOException {
        for (Path candidate : List.of(Paths.get("src/main/resources/templates/blueprints"),
                Paths.get("ai-automation-backend/src/main/resources/templates/blueprints"))) {
            if (Files.isDirectory(candidate)) {
                try (Stream<Path> stream = Files.list(candidate)) {
                    return stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
                }
            }
        }
        throw new IllegalStateException("templates/blueprints nicht gefunden ab " + Paths.get("").toAbsolutePath());
    }
}
