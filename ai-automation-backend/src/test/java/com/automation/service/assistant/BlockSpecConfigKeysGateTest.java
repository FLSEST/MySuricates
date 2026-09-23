package com.automation.service.assistant;

import com.automation.executor.BlockExecutor;
import com.automation.service.assistant.context.BlockTypesIndexSection;
import com.automation.service.assistant.fetch.BlockSpecs;
import com.automation.service.assistant.fetch.DocsFetchHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.withSettings;

/**
 * Drift gate for the assistant's block specifications (TODO c4b81017): every config key that
 * {@code <fetch type="block-spec"/>} or a summary of {@link BlockTypesIndexSection} names has to be
 * read where that block type runs. About ten of the sixteen older specifications named keys no
 * executor reads ({@code skill_id}, {@code provider_id}, {@code true_blocks}, …), and the assistant
 * built configs from them that did nothing.
 *
 * <p><b>Named</b> — the conventions {@link BlockSpecs} documents: a key in backticks; a key of the
 * one-line {@code Example config:} JSON, which must parse (objects inside its lists and a nested
 * block's {@code config} count, free-form maps such as {@code params} do not); and any other
 * snake_case word outside double quotes and {@code {{ placeholders }}}, so an unmarked key is
 * checked all the same. A single-word key without backticks is not seen — hence the rule that the
 * key lists start with a backticked key.
 *
 * <p><b>Read</b> — a string literal in the code (comments and text blocks skipped) of the executor
 * that supports the type, of the classes it hands its config to ({@link #DELEGATES}), of
 * {@code BlockAiSettings} when it resolves provider/model/effort, of {@code ExecutionMode} when it
 * checks {@code execution_mode}, and of {@code BlockDefinition} ({@code on_failure}, read by the
 * engine for every block).
 */
class BlockSpecConfigKeysGateTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final String EXAMPLE = "Example config:";
    private static final String HINT = "Fix the text — the executor is the source of truth. If a helper class"
            + " of the executor reads the key, list it in DELEGATES.";

    /** Classes an executor hands (part of) its config to, by block type; a directory stands for its sources. */
    private static final Map<String, List<String>> DELEGATES = Map.of(
            "agentic", List.of("com/automation/executor/blocks/AgenticBlockConfig.java"),
            // stage options: one class per stage
            "compress", List.of("com/automation/executor/compress"),
            "dashboard-report", List.of("com/automation/executor/blocks/DashboardReportCandidate.java"));

    /** provider/model/effort, resolved through ExecutionContext#providerFor/modelFor/effortFor. */
    private static final Pattern RESOLVES_AI_SETTINGS = Pattern.compile("\\b(?:providerFor|modelFor|effortFor)\\(");
    private static final String AI_SETTINGS = "com/automation/executor/BlockAiSettings.java";
    private static final Pattern CHECKS_EXECUTION_MODE = Pattern.compile("\\bExecutionMode\\.");
    private static final String EXECUTION_MODE = "com/automation/executor/ExecutionMode.java";
    /** on_failure — BlockDefinition#shouldStopOnFailure, for every block. */
    private static final String ENGINE = "com/automation/entity/BlockDefinition.java";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{.*?}}");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`\\n]*)`");
    private static final Pattern QUOTED = Pattern.compile("\"(?:\\\\.|[^\"\\\\\\n])*\"");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern SNAKE_CASE = Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");
    private static final Pattern KEY_LIST = Pattern.compile("^(Required config|Optional):\\s*(.*)");
    /** Comments and text blocks (skipped), char literals, and string literals (group 1 = content). */
    private static final Pattern JAVA_TOKENS = Pattern.compile(
            "//[^\\n]*|/\\*[\\s\\S]*?\\*/|\"\"\"[\\s\\S]*?\"\"\"|'(?:\\\\.|[^'\\\\])*'|\"((?:\\\\.|[^\"\\\\\\n])*)\"");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Test
    void everyKeyABlockSpecNamesIsReadWhereTheBlockTypeRuns() throws IOException {
        DocsFetchHandler docs = new DocsFetchHandler();
        List<String> problems = new ArrayList<>();
        for (String type : new TreeSet<>(BlockSpecs.types())) {
            String spec = docs.formatBlockSpec(type);
            String where = "block-spec '" + type + "'";
            long examples = spec.lines().filter(line -> line.startsWith(EXAMPLE)).count();
            if (examples != 1) {
                problems.add(where + ": needs exactly one '" + EXAMPLE + " {…}' line, has " + examples);
            }
            spec.lines().map(KEY_LIST::matcher).filter(Matcher::matches)
                    .filter(m -> !m.group(2).startsWith("`") && !m.group(2).startsWith("none"))
                    .forEach(m -> problems.add(where + ": '" + m.group(1) + ":' must start with a backticked key"
                            + " (or \"none\") — a key without backticks is invisible to this gate"));
            Set<String> keys = namedKeys(spec, where, problems);
            if (keys.isEmpty()) {
                problems.add(where + ": names no config key at all — this gate would measure nothing");
            }
            checkRead(type, where, keys, problems);
        }
        assertThat(problems).as(HINT).isEmpty();
    }

    @Test
    void everyKeyAnIndexSummaryNamesIsReadWhereTheBlockTypeRuns() throws IOException {
        List<String> problems = new ArrayList<>();
        for (BlockTypesIndexSection.BlockTypeEntry entry : BlockTypesIndexSection.BLOCK_TYPES) {
            String where = "BlockTypesIndexSection summary of '" + entry.type() + "'";
            checkRead(entry.type(), where, namedKeys(entry.summary(), where, problems), problems);
        }
        assertThat(problems).as(HINT).isEmpty();
    }

    /** What the gate counts as a named key — and what it cannot see (unmarked single words). */
    @Test
    void namedKeys_seesBacktickedKeysExampleKeysAndUnmarkedSnakeCase() {
        List<String> problems = new ArrayList<>();
        Set<String> keys = namedKeys("""
                **Block type: x** — Runs `command` with {{ some_variable }} and `{{ item }}`.
                Optional: `retries` (int), max_turns (int), mode ("head_tail" | "ai"), label (string).
                Example config: {"command": "run", "cases": [{"value": "A", "blocks": [{"type": "shell", \
                "config": {"timeout_seconds": 5}}]}], "params": {"freeForm": 1, "snake_param": 2}}
                """, "sample", problems);

        assertThat(problems).isEmpty();
        assertThat(keys).containsExactlyInAnyOrder("command", "retries", "max_turns",
                "cases", "value", "blocks", "type", "config", "timeout_seconds", "params");
    }

    @Test
    void namedKeys_reportsAnExampleThatIsNotJson() {
        List<String> problems = new ArrayList<>();
        namedKeys("Example config: { \"items\": \"{{ list }}\", \"blocks\": [...] }", "sample", problems);

        assertThat(problems).singleElement().asString().contains("sample").contains("not valid JSON");
    }

    @Test
    void stringLiterals_skipCommentsAndTextBlocks() {
        String source = """
                // config.get("commented")
                /* "blockComment" */ Object a = config.get("real"); char q = '"';
                String b = \"""
                    "inTextBlock"
                    \""";
                String c = "http://x"; String d = "after \\"escaped\\"";
                """;

        assertThat(stringLiterals(source)).containsExactlyInAnyOrder("real", "http://x", "after \\\"escaped\\\"");
    }

    @Test
    void delegatesBelongToSpecifiedTypesAndExist() {
        assertThat(BlockSpecs.types()).containsAll(DELEGATES.keySet());
        DELEGATES.values().stream().flatMap(List::stream)
                .forEach(path -> assertThat(MAIN.resolve(path)).as("delegate %s", path).exists());
        Stream.of(AI_SETTINGS, EXECUTION_MODE, ENGINE)
                .forEach(path -> assertThat(MAIN.resolve(path)).as("shared reader %s", path).exists());
    }

    // ---- named keys ------------------------------------------------------------------------------

    private static Set<String> namedKeys(String text, String where, List<String> problems) {
        Set<String> keys = new TreeSet<>();
        StringBuilder prose = new StringBuilder();
        for (String line : text.split("\n")) {
            if (line.startsWith(EXAMPLE)) {
                exampleKeys(line.substring(EXAMPLE.length()).strip(), where, problems, keys);
            } else {
                prose.append(line).append('\n');
            }
        }
        String rest = PLACEHOLDER.matcher(prose).replaceAll(" ");
        Matcher backticked = BACKTICKED.matcher(rest);
        while (backticked.find()) {
            if (KEY.matcher(backticked.group(1)).matches()) {
                keys.add(backticked.group(1));
            }
        }
        rest = QUOTED.matcher(BACKTICKED.matcher(rest).replaceAll(" ")).replaceAll(" ");
        Matcher unmarked = SNAKE_CASE.matcher(rest);
        while (unmarked.find()) {
            keys.add(unmarked.group());
        }
        return keys;
    }

    private static void exampleKeys(String json, String where, List<String> problems, Set<String> keys) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            problems.add(where + ": '" + EXAMPLE + "' is not valid JSON — " + e.getOriginalMessage());
            return;
        }
        if (!root.isObject()) {
            problems.add(where + ": '" + EXAMPLE + "' must be a JSON object");
            return;
        }
        collectKeys(root, keys);
    }

    private static void collectKeys(JsonNode object, Set<String> keys) {
        for (Map.Entry<String, JsonNode> field : object.properties()) {
            keys.add(field.getKey());
            JsonNode value = field.getValue();
            if (value.isArray()) {
                value.forEach(element -> {
                    if (element.isObject()) collectKeys(element, keys);
                });
            } else if (value.isObject() && "config".equals(field.getKey())) {
                collectKeys(value, keys); // the config of a nested block
            }
        }
    }

    // ---- where a block type reads its config -----------------------------------------------------

    private static void checkRead(String type, String where, Set<String> keys, List<String> problems)
            throws IOException {
        List<Path> sources = sourcesOf(type);
        Set<String> literals = new HashSet<>();
        for (Path source : sources) {
            literals.addAll(stringLiterals(Files.readString(source)));
        }
        List<String> names = sources.stream().map(p -> p.getFileName().toString()).toList();
        keys.stream().filter(key -> !literals.contains(key))
                .forEach(key -> problems.add(where + " names `" + key + "`, but none of " + names + " reads it"));
    }

    private static List<Path> sourcesOf(String type) throws IOException {
        Path executor = executorSources().get(type);
        assertThat(executor).as("source of the BlockExecutor for '%s'", type).isNotNull().exists();
        List<Path> sources = new ArrayList<>(List.of(executor, MAIN.resolve(ENGINE)));
        String code = withoutComments(Files.readString(executor));
        if (RESOLVES_AI_SETTINGS.matcher(code).find()) {
            sources.add(MAIN.resolve(AI_SETTINGS));
        }
        if (CHECKS_EXECUTION_MODE.matcher(code).find()) {
            sources.add(MAIN.resolve(EXECUTION_MODE));
        }
        for (String delegate : DELEGATES.getOrDefault(type, List.of())) {
            Path path = MAIN.resolve(delegate);
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.list(path)) {
                    files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(sources::add);
                }
            } else {
                sources.add(path);
            }
        }
        return sources;
    }

    private static Map<String, Path> executorSources;

    private static synchronized Map<String, Path> executorSources() {
        if (executorSources == null) {
            Map<String, Path> byType = new TreeMap<>();
            for (Class<?> cls : BlockTypeListsGuardTest.executorClasses()) {
                BlockExecutor probe = (BlockExecutor) Mockito.mock(cls, withSettings().defaultAnswer(CALLS_REAL_METHODS));
                probe.supportedTypes().forEach(type ->
                        byType.put(type, MAIN.resolve(cls.getName().replace('.', '/') + ".java")));
            }
            executorSources = byType;
        }
        return executorSources;
    }

    private static Set<String> stringLiterals(String javaSource) {
        Set<String> literals = new HashSet<>();
        Matcher token = JAVA_TOKENS.matcher(javaSource);
        while (token.find()) {
            if (token.group(1) != null) {
                literals.add(token.group(1));
            }
        }
        return literals;
    }

    private static String withoutComments(String javaSource) {
        return JAVA_TOKENS.matcher(javaSource).replaceAll(token ->
                token.group().startsWith("/") ? " " : Matcher.quoteReplacement(token.group()));
    }
}
