package com.automation.architecture;

import com.automation.executor.BlockExecutor;
import com.automation.service.workflow.analyzer.BlockConfigSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.objenesis.ObjenesisStd;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift guard of the backend side (TODO f5153c47): every config key that an executor reads must be in the
 * block config schema of its block type — otherwise the analyzer reports an effective key as
 * unknown, and the editor warns about it. The frontend test {@code blockConfigSchema.test.ts} checks the same
 * schema against the shipped definitions, this one against the code.
 *
 * <p>Measured on the source text: {@code config.get("…")}, {@code getOrDefault}, {@code containsKey} on
 * {@code config} or {@code block.getConfig()} and helpers with the config as first argument
 * ({@code StageConfig.intValue(config, "…")}). If an executor reads
 * via a shared helper, its keys count too ({@link #DELEGATES}). The mapping executor →
 * block type comes from {@link BlockExecutor#supportedTypes()}, as with the {@code BlockTypeRegistry}.
 */
class ExecutorConfigKeyDriftTest {

    private static final Path SOURCES = Path.of("src/main/java");

    private static final String RECEIVER = "(?:config|originalConfig|block\\.getConfig\\(\\))";
    /** {@code config.get("key")}, {@code getOrDefault}, {@code containsKey}. */
    private static final Pattern DIRECT = Pattern.compile(
            "\\b" + RECEIVER + "\\s*\\.\\s*(?:get|getOrDefault|containsKey)\\(\\s*\"([A-Za-z_]+)\"");
    /** Helfer mit der Config als erstem Argument: {@code StageConfig.intValue(config, "max_chars", …)}. */
    private static final Pattern HELPER = Pattern.compile(
            "\\b[\\w.]+\\(\\s*" + RECEIVER + "\\s*,\\s*\"([A-Za-z_]+)\"");

    /**
     * Shared helpers that read config keys for the executor: call in the executor (with its own
     * {@code config} as argument) → method of the helper whose keys count too. Only the body of this
     * method counts, not the whole file. {@link #delegatesStillReadKeys()} ensures that every method
     * still yields keys — if one moves, the test would otherwise silently measure less.
     */
    private record Delegate(String file, String method) {}

    private static final Map<String, Delegate> DELEGATES = Map.of(
            "buildResolvedShellBlock(config,",
            new Delegate("com/automation/service/cli/CommandExecutorService.java", "buildResolvedShellBlock"),
            // InnerStep (see OWN_INNER_STEP) builds its shell step from the config of the block it runs for
            "buildResolvedShellBlock(block.getConfig(),",
            new Delegate("com/automation/service/cli/CommandExecutorService.java", "buildResolvedShellBlock"),
            "AgenticBlockConfig.from(block.getConfig(),",
            new Delegate("com/automation/executor/blocks/AgenticBlockConfig.java", "from"),
            "ContainerToolSpec.from(config,",
            new Delegate("com/automation/executor/blocks/ContainerToolSpec.java", "from"));

    /** {@code ExecutionMode} reads its key via a constant, not via a literal. */
    private static final String EXECUTION_MODE_CALL = "ExecutionMode.isShell(config";

    /**
     * foreach, while, conditional and retry hand their own block to {@code InnerStep}, so every key InnerStep
     * reads — anywhere in its file, through its own delegates too — counts for them as if they read it
     * themselves. switch hands it its case blocks ({@code new InnerStep(nestedBlock, …)}): those reads belong
     * to the case block's type (shell/simple) and are deliberately not measured for switch, like its other
     * {@code nestedConfig} reads.
     */
    private static final String OWN_INNER_STEP = "new InnerStep(block,";
    private static final Path INNER_STEP = SOURCES.resolve("com/automation/executor/blocks/InnerStep.java");

    /** Keys that an executor reads but that deliberately do not belong in the schema — with a reason. */
    private static final Map<String, String> EXCEPTIONS = Map.of();

    @Test
    void jederGeleseneSchluesselStehtImSchemaSeinesBlocktyps() throws IOException {
        BlockConfigSchema schema = BlockConfigSchema.get();
        Map<String, Set<String>> readByType = keysReadPerType();
        List<String> problems = new ArrayList<>();
        int total = 0;

        for (Map.Entry<String, Set<String>> entry : readByType.entrySet()) {
            Set<String> known = schema.fieldsFor(entry.getKey()).keySet();
            for (String key : entry.getValue()) {
                total++;
                if (!known.contains(key) && !EXCEPTIONS.containsKey(entry.getKey() + "." + key)) {
                    problems.add(entry.getKey() + "." + key);
                }
            }
        }

        System.out.println("Executor-Schlüssel je Blocktyp: " + readByType);
        assertThat(total).as("der Test misst nichts mehr — Muster oder Pfade prüfen").isGreaterThan(80);
        assertThat(problems)
                .as("Executor liest Schlüssel, die blockConfigSchema.json (ai-automation-frontend/src/data) nicht kennt")
                .isEmpty();
    }

    @Test
    void schemaUndExecutorenKennenDieselbenBlocktypen() throws IOException {
        assertThat(new TreeSet<>(BlockConfigSchema.get().blockTypes())).isEqualTo(keysReadPerType().keySet());
    }

    @Test
    void delegatesStillReadKeys() throws IOException {
        for (Delegate delegate : DELEGATES.values()) {
            assertThat(literalKeys(body(delegate))).as(delegate.toString()).isNotEmpty();
        }
        assertThat(Files.readString(SOURCES.resolve("com/automation/executor/ExecutionMode.java")))
                .contains("KEY = \"execution_mode\"");
        assertThat(keysIn(innerStepSource())).as("keys read through InnerStep").isNotEmpty();
    }

    /** Block type → keys read, across all executors on the classpath. */
    private static Map<String, Set<String>> keysReadPerType() throws IOException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(BlockExecutor.class));
        ObjenesisStd objenesis = new ObjenesisStd();
        Map<String, Set<String>> byType = new TreeMap<>();

        for (BeanDefinition candidate : scanner.findCandidateComponents("com.automation")) {
            Class<?> type;
            try {
                type = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
            // Created without a constructor: supportedTypes() returns a constant and needs no dependencies
            BlockExecutor executor = (BlockExecutor) objenesis.newInstance(type);
            String source = Files.readString(SOURCES.resolve(type.getName().replace('.', '/') + ".java"));
            Set<String> keys = keysIn(source);
            for (String blockType : executor.supportedTypes()) {
                byType.computeIfAbsent(blockType, t -> new TreeSet<>()).addAll(keys);
            }
        }
        return byType;
    }

    private static Set<String> keysIn(String source) throws IOException {
        Set<String> keys = literalKeys(source);
        for (Map.Entry<String, Delegate> delegate : DELEGATES.entrySet()) {
            if (source.contains(delegate.getKey())) keys.addAll(literalKeys(body(delegate.getValue())));
        }
        if (source.contains(EXECUTION_MODE_CALL)) keys.add("execution_mode");
        if (source.contains(OWN_INNER_STEP)) keys.addAll(keysIn(innerStepSource()));
        return keys;
    }

    /** InnerStep's source, without the trigger text itself so that scanning it cannot recurse. */
    private static String innerStepSource() throws IOException {
        return Files.readString(INNER_STEP).replace(OWN_INNER_STEP, "");
    }

    /** The body of a static method: from its declaration to the closing brace at method level. */
    private static String body(Delegate delegate) throws IOException {
        String source = Files.readString(SOURCES.resolve(delegate.file()));
        Matcher declaration = Pattern.compile("\\bstatic\\s+[\\w<>.,\\s]+\\s" + delegate.method() + "\\(").matcher(source);
        assertThat(declaration.find()).as("Methode %s nicht gefunden", delegate).isTrue();
        int end = source.indexOf("\n    }\n", declaration.start());
        assertThat(end).as("Ende von %s nicht gefunden", delegate).isPositive();
        return source.substring(declaration.start(), end);
    }

    private static Set<String> literalKeys(String source) {
        Set<String> keys = new TreeSet<>();
        for (Pattern pattern : List.of(DIRECT, HELPER)) {
            Matcher matcher = pattern.matcher(source);
            while (matcher.find()) keys.add(matcher.group(1));
        }
        return keys;
    }
}
