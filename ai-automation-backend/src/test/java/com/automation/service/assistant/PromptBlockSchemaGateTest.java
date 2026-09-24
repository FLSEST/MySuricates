package com.automation.service.assistant;

import com.automation.config.WishConfig;
import com.automation.dto.request.WorkflowFactoryRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Wish;
import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowMemoryRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowTemplateRepository;
import com.automation.service.conversion.WorkflowConversionPrompt;
import com.automation.service.dialog.DialogConversionPrompt;
import com.automation.service.factory.FactoryPromptBuilder;
import com.automation.service.pool.WorkflowPoolService;
import com.automation.service.wish.WishDecomposePromptBuilder;
import com.automation.service.wish.WishPromptHelper;
import com.automation.service.workflow.AgentInstructionsService;
import com.automation.service.workflow.WorkflowOptimizationPrompt;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Drift gate for the prompts that let an AI build workflows (TODO cf8db6f8): every block type such a
 * prompt names must be one an executor supports, spelled exactly, and every config key it names must
 * be read where that type runs ({@link ExecutorConfigReads}). The wish, factory and dialog-import
 * prompts described a schema of their own — type {@code forEach} (refused on save),
 * {@code then_blocks}, {@code if_true}, {@code items_expression}, {@code delay_seconds} — and the
 * workflows built from it failed or did nothing. The prompts that convert an agentic run into a
 * workflow and that optimize a workflow followed (TODO b07704ca).
 *
 * <p><b>Which type a line is about</b>, first match wins: inside a specification section (from a
 * {@code **Block type: X**} headline to the next blank line) that type; a block object on the line
 * ({@code "type": "X"}, alternatives separated by {@code |} all count); a list item that starts with
 * the type ({@code - **X**}, {@code - "X"}, {@code - `X`}, {@code - X:}); otherwise none.
 *
 * <p><b>Keys on a line</b>: quoted keys followed by a colon, keys in backticks, and snake_case words
 * outside quotes and {@code {{ placeholders }}}. The block fields {@code type}, {@code config} and
 * {@code name} are no config keys. On a line about no type, only the snake_case keys are checked —
 * against every block type.
 *
 * <p><b>Spelling</b>: a word that is a block type in other case, with {@code _} or without {@code -}
 * ({@code forEach}, {@code sub_workflow}) is an error; a capital first letter is not.
 *
 * <p><b>Execution mode</b> (TODO ce4abac1): retry, foreach, while and conditional hand their command to
 * the AI unless {@code execution_mode} is "shell", and an AI builds what an example shows. So an example
 * of these types that carries a command names its mode — whether a command is meant for the shell
 * cannot be told from its text reliably. An example is a JSON object on the line: from each
 * {@code "type": "X"} to the next, or the object on a line whose section or list item names the type.
 */
class PromptBlockSchemaGateTest {

    private static final Pattern HEADLINE = Pattern.compile("^\\*\\*Block type: ([^*]+)\\*\\*");
    private static final Pattern TYPE_FIELD = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern LIST_ITEM = Pattern.compile(
            "^\\s*[-*]\\s+(?:\\*\\*([^*]+)\\*\\*|\"([^\"]+)\"|`([^`]+)`|([A-Za-z][\\w-]*):)");
    private static final Pattern WORD = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*[A-Za-z0-9]");
    private static final Pattern QUOTED_KEY = Pattern.compile("\"([a-z][a-z0-9_]*)\"\\s*:");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{.*?}}");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`\\n]*)`");
    private static final Pattern QUOTED = Pattern.compile("\"(?:\\\\.|[^\"\\\\\\n])*\"");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern SNAKE_CASE = Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");
    private static final Set<String> BLOCK_FIELDS = Set.of("type", "config", "name");
    /** The types whose command runs as an AI prompt unless {@code execution_mode} is "shell" ({@code ExecutionMode}). */
    private static final Set<String> MODE_SWITCHED = Set.of("retry", "foreach", "while", "conditional");
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{\\s*\"");
    private static final Pattern COMMAND_KEY = Pattern.compile(
            "\"(?:command|condition|condition_command|then_command|else_command)\"\\s*:");
    private static final Pattern EXECUTION_MODE_KEY = Pattern.compile("\"execution_mode\"\\s*:");

    @Test
    void everyBlockTypeAndConfigKeyAWorkflowBuildingPromptNamesExists() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> prompt : prompts().entrySet()) {
            check(prompt.getKey(), prompt.getValue(), problems);
        }
        assertThat(problems).as(BlockSpecConfigKeysGateTest.HINT).isEmpty();
    }

    /** What the gate sees — the three kinds of type attribution, spelling, the fall-back to every type, the execution mode. */
    @Test
    void check_attributesKeysToTheirTypeAndCatchesTheOldSchema() throws IOException {
        List<String> problems = new ArrayList<>();
        check("sample", """
                - **forEach**: Iterates over items. Config: `{"items": "x", "variable": "item", "command": "..."}`
                - conditional: Branch. Config: { "condition": "...", "if_true": "..." }
                {"type": "shell", "config": {"command": "npm test", "on_failure": "stop"}}
                **Block type: retry** — Runs `command` again, shell mode: exit code 0 = done.
                Example config: {"command": "npm test", "max_retries": 2, "delay_seconds": 5}

                Set `max_iterations` and `invented_key`; use {{ previous_output }} and "quoted_value".
                """, problems);

        assertThat(problems).extracting(p -> p.replaceAll(" — .*", "")).containsExactlyInAnyOrder(
                "sample:1 spells block type 'foreach' as 'forEach'",
                "sample:1 names `variable` for [foreach], but none reads it",
                "sample:1 shows a foreach example without `execution_mode`",
                "sample:2 names `if_true` for [conditional], but none reads it",
                "sample:2 shows a conditional example without `execution_mode`",
                "sample:5 names `delay_seconds` for [retry], but none reads it",
                "sample:5 shows a retry example without `execution_mode`",
                "sample:7 names `invented_key`, but no block type reads it");
    }

    /**
     * TODO ce4abac1: retry, foreach, while and conditional hand their command to the AI unless
     * {@code execution_mode} is "shell". The conversion prompt showed a retry with "npm test" and no mode,
     * and the AI copied it — the shell command ran as a prompt (b07704ca). Line 2 is that example.
     */
    @Test
    void check_demandsTheExecutionModeInExamplesOfTheWrapperTypes() throws IOException {
        List<String> problems = new ArrayList<>();
        check("sample", """
                {"type": "shell", "config": {"command": "npm ci"}}
                {"type": "retry", "config": {"command": "npm test", "max_retries": 3, "on_failure": "stop"}}
                {"type": "retry", "config": {"command": "npm test", "execution_mode": "shell", "max_retries": 3}}
                {"type": "foreach", "config": {"items": ["a"], "command": "Summarize {{ item }}", "execution_mode": "ai"}}, \
                {"type": "while", "config": {"command": "./next-batch.sh"}}
                **Block type: conditional** — If/else.
                Example config: {"condition": "test -f package.json", "then_command": "npm test"}
                Required config: `condition` (string) — shell mode: exit code 0 = true.
                """, problems);

        assertThat(problems).extracting(p -> p.replaceAll(" — .*", "")).containsExactlyInAnyOrder(
                "sample:2 shows a retry example without `execution_mode`",
                "sample:4 shows a while example without `execution_mode`",
                "sample:6 shows a conditional example without `execution_mode`");
    }

    // ---- the prompts -----------------------------------------------------------------------------

    static Map<String, String> prompts() throws IOException {
        AgentInstructionsService agents = mock(AgentInstructionsService.class);
        when(agents.buildInstructionPrefix(any())).thenReturn("");
        WishDecomposePromptBuilder wish = new WishDecomposePromptBuilder(mock(WorkflowRepository.class),
                mock(ProjectRepository.class), mock(WorkflowTemplateRepository.class),
                mock(WorkflowMemoryRepository.class), mock(WorkflowPoolService.class), agents,
                mock(WishPromptHelper.class), new WishConfig());
        FactoryPromptBuilder factory = new FactoryPromptBuilder(agents);
        WorkflowFactoryRequest request = new WorkflowFactoryRequest("Nightly build", "Builds nightly", "DEVOPS",
                "proj-1", Map.of("branch", "main"), false, false, 2, null);

        Map<String, String> prompts = new LinkedHashMap<>();
        prompts.put("WishDecomposePromptBuilder.buildSystemPrompt", wish.buildSystemPrompt(new Wish()));
        prompts.put("FactoryPromptBuilder.buildSystemPrompt", factory.buildSystemPrompt());
        prompts.put("FactoryPromptBuilder.buildGeneratorPrompt", factory.buildGeneratorPrompt(request));
        prompts.put("FactoryPromptBuilder.buildAutoFixPrompt", factory.buildAutoFixPrompt("WF", "does things", "[]", "exit 1"));
        prompts.put("factory/codex-factory-instructions.md",
                Files.readString(Path.of("src/main/resources/factory/codex-factory-instructions.md")));
        prompts.put("DialogConversionPrompt.build", DialogConversionPrompt.build("/project", 4, "[ASSISTANT]\n[Bash] mvn test"));
        prompts.put("WorkflowConversionPrompt.build", WorkflowConversionPrompt.build(
                "Goal: Make the tests pass\n\nStep 1: npm ci (exit: 0)\nStep 2: npm test (exit: 0)\n"));
        prompts.put("WorkflowOptimizationPrompt.build", WorkflowOptimizationPrompt.build(Workflow.builder()
                .name("Nightly build").description("Builds nightly").instructions("Be brief")
                .blocks(List.of(BlockDefinition.builder().type("simple").config(Map.of("command", "Summarize")).build()))
                .build(), "[]"));
        return prompts;
    }

    // ---- the check -------------------------------------------------------------------------------

    static void check(String where, String text, List<String> problems) throws IOException {
        Map<String, String> byNormalized = new LinkedHashMap<>();
        ExecutorConfigReads.types().forEach(type -> byNormalized.put(normalized(type), type));
        String section = null;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String at = where + ":" + (i + 1);
            Matcher headline = HEADLINE.matcher(line);
            if (headline.find()) {
                section = headline.group(1);
            }
            misspelledTypes(line, at, byNormalized, problems);
            List<String> types = section != null ? List.of(section) : typesOf(line, byNormalized);
            types.stream().filter(type -> !ExecutorConfigReads.types().contains(type)).forEach(type ->
                    problems.add(at + " names block type '" + type + "', which no executor supports"));
            checkKeys(line, at, types.stream().filter(ExecutorConfigReads.types()::contains).toList(), problems);
            missingExecutionMode(line, at, section != null, types, problems);
            if (line.isBlank()) {
                section = null;
            }
        }
    }

    private static List<String> typesOf(String line, Map<String, String> byNormalized) {
        List<String> types = new ArrayList<>();
        Matcher field = TYPE_FIELD.matcher(line);
        while (field.find()) {
            types.addAll(List.of(field.group(1).split("\\|")));
        }
        if (!types.isEmpty()) {
            return types;
        }
        Matcher item = LIST_ITEM.matcher(line);
        if (item.find()) {
            for (int group = 1; group <= item.groupCount(); group++) {
                String type = item.group(group) == null ? null : byNormalized.get(normalized(item.group(group)));
                if (type != null) {
                    return List.of(type);
                }
            }
        }
        return List.of();
    }

    private static void misspelledTypes(String line, String at, Map<String, String> byNormalized, List<String> problems) {
        Matcher word = WORD.matcher(line);
        while (word.find()) {
            String type = byNormalized.get(normalized(word.group()));
            if (type != null && !word.group().equals(type)
                    && !word.group().equals(Character.toUpperCase(type.charAt(0)) + type.substring(1))) {
                problems.add(at + " spells block type '" + type + "' as '" + word.group() + "'");
            }
        }
    }

    private static void checkKeys(String line, String at, List<String> types, List<String> problems)
            throws IOException {
        Set<String> keys = new TreeSet<>();
        Matcher quotedKey = QUOTED_KEY.matcher(line);
        while (quotedKey.find()) {
            keys.add(quotedKey.group(1));
        }
        String rest = PLACEHOLDER.matcher(line).replaceAll(" ");
        Matcher backticked = BACKTICKED.matcher(rest);
        while (backticked.find()) {
            if (KEY.matcher(backticked.group(1)).matches()) {
                keys.add(backticked.group(1));
            }
        }
        Matcher snake = SNAKE_CASE.matcher(QUOTED.matcher(BACKTICKED.matcher(rest).replaceAll(" ")).replaceAll(" "));
        while (snake.find()) {
            keys.add(snake.group());
        }
        keys.removeAll(BLOCK_FIELDS);
        if (types.isEmpty()) {
            Set<String> readAnywhere = new HashSet<>();
            for (String type : ExecutorConfigReads.types()) {
                readAnywhere.addAll(ExecutorConfigReads.literals(type));
            }
            keys.stream().filter(key -> key.contains("_") && !readAnywhere.contains(key)).forEach(key ->
                    problems.add(at + " names `" + key + "`, but no block type reads it — " + line.strip()));
            return;
        }
        Set<String> read = new HashSet<>();
        for (String type : types) {
            read.addAll(ExecutorConfigReads.literals(type));
        }
        keys.stream().filter(key -> !read.contains(key)).forEach(key ->
                problems.add(at + " names `" + key + "` for " + types + ", but none reads it — " + line.strip()));
    }

    /** {@code types}: what the line is about — its section, or a list item; block objects are split per object. */
    private static void missingExecutionMode(String line, String at, boolean inSection, List<String> types,
                                             List<String> problems) {
        Matcher object = JSON_OBJECT.matcher(line);
        if (!object.find()) {
            return;
        }
        String json = line.substring(object.start());
        Map<String, List<String>> examples = new LinkedHashMap<>();
        Matcher field = TYPE_FIELD.matcher(json);
        if (!inSection && field.find()) {
            List<Integer> starts = new ArrayList<>();
            List<List<String>> objectTypes = new ArrayList<>();
            do {
                starts.add(field.start());
                objectTypes.add(List.of(field.group(1).split("\\|")));
            } while (field.find());
            for (int k = 0; k < starts.size(); k++) {
                int end = k + 1 < starts.size() ? starts.get(k + 1) : json.length();
                examples.put(json.substring(starts.get(k), end), objectTypes.get(k));
            }
        } else {
            examples.put(json, types);
        }
        examples.forEach((example, exampleTypes) -> {
            if (COMMAND_KEY.matcher(example).find() && !EXECUTION_MODE_KEY.matcher(example).find()) {
                exampleTypes.stream().filter(MODE_SWITCHED::contains).forEach(type -> problems.add(at + " shows a "
                        + type + " example without `execution_mode` — its command runs as an AI prompt; name the "
                        + "mode (\"shell\" for a shell command, \"ai\" for a prompt): " + line.strip()));
            }
        });
    }

    private static String normalized(String word) {
        return word.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }
}
