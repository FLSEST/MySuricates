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
 * Drift-Wächter der Backend-Seite (TODO f5153c47): Jeder Config-Schlüssel, den ein Executor liest, muss im
 * Block-Config-Schema seines Blocktyps stehen — sonst meldet der Analyzer einen wirksamen Schlüssel als
 * unbekannt, und der Editor warnt davor. Der Frontend-Test {@code blockConfigSchema.test.ts} hält dasselbe
 * Schema gegen die ausgelieferten Definitionen, dieser hier gegen den Code.
 *
 * <p>Gemessen wird am Quelltext: {@code config.get("…")}, {@code getOrDefault}, {@code containsKey} auf
 * {@code config} oder {@code block.getConfig()} und Helfer mit der Config als erstem Argument
 * ({@code StageConfig.intValue(config, "…")}). Liest ein Executor
 * über einen geteilten Helfer, zählen dessen Schlüssel mit ({@link #DELEGATES}). Die Zuordnung Executor →
 * Blocktyp kommt aus {@link BlockExecutor#supportedTypes()}, wie beim {@code BlockTypeRegistry}.
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
     * Geteilte Helfer, die Config-Schlüssel für den Executor lesen: Aufruf im Executor (mit der eigenen
     * {@code config} als Argument) → Methode des Helfers, deren Schlüssel mitzählen. Nur der Rumpf dieser
     * Methode zählt, nicht die ganze Datei. {@link #delegatesStillReadKeys()} hält fest, dass jede Methode
     * noch Schlüssel liefert — zieht eine um, misst der Test sonst still weniger.
     */
    private record Delegate(String file, String method) {}

    private static final Map<String, Delegate> DELEGATES = Map.of(
            "buildResolvedShellBlock(config,",
            new Delegate("com/automation/service/cli/CommandExecutorService.java", "buildResolvedShellBlock"),
            "AgenticBlockConfig.from(block.getConfig(),",
            new Delegate("com/automation/executor/blocks/AgenticBlockConfig.java", "from"));

    /** {@code ExecutionMode} liest seinen Schlüssel über eine Konstante, nicht über ein Literal. */
    private static final String EXECUTION_MODE_CALL = "ExecutionMode.isShell(config";

    /** Schlüssel, die ein Executor liest, die aber bewusst nicht ins Schema gehören — mit Grund. */
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
    }

    /** Blocktyp → gelesene Schlüssel, über alle Executoren im Klassenpfad. */
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
            // Ohne Konstruktor erzeugt: supportedTypes() liefert eine Konstante und braucht keine Abhängigkeiten
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
        return keys;
    }

    /** Der Rumpf einer statischen Methode: von ihrer Deklaration bis zur schließenden Klammer auf Methodenebene. */
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
