package com.automation.service.assistant;

import com.automation.executor.BlockExecutor;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.withSettings;

/**
 * Which config keys a block type reads, measured in the code that runs it — shared by the drift
 * gates for texts that name config keys ({@code BlockSpecConfigKeysGateTest},
 * {@code PromptBlockSchemaGateTest}, TODO c4b81017 and cf8db6f8).
 *
 * <p><b>Read</b> means a string literal in the code (comments and text blocks skipped) of the
 * executor that supports the type, of the classes it hands its config to ({@link #DELEGATES}), of
 * {@code BlockAiSettings} when it resolves provider/model/effort, of {@code ExecutionMode} when it
 * checks {@code execution_mode}, and of {@code BlockDefinition} ({@code on_failure}, read by the
 * engine for every block).
 *
 * <p>Public for {@code BlockSpecsTest}, which asks which executors resolve the AI overrides.
 */
public final class ExecutorConfigReads {

    static final Path MAIN = Path.of("src/main/java");

    /** Classes an executor hands (part of) its config to, by block type; a directory stands for its sources. */
    static final Map<String, List<String>> DELEGATES = Map.of(
            "agentic", List.of("com/automation/executor/blocks/AgenticBlockConfig.java"),
            // stage options: one class per stage
            "compress", List.of("com/automation/executor/compress"),
            "dashboard-report", List.of("com/automation/executor/blocks/DashboardReportCandidate.java"));

    /** provider/model/effort, resolved through ExecutionContext#providerFor/modelFor/effortFor. */
    private static final Pattern RESOLVES_AI_SETTINGS = Pattern.compile("\\b(?:providerFor|modelFor|effortFor)\\(");
    static final String AI_SETTINGS = "com/automation/executor/BlockAiSettings.java";
    private static final Pattern CHECKS_EXECUTION_MODE = Pattern.compile("\\bExecutionMode\\.");
    static final String EXECUTION_MODE = "com/automation/executor/ExecutionMode.java";
    /** on_failure — BlockDefinition#shouldStopOnFailure, for every block. */
    static final String ENGINE = "com/automation/entity/BlockDefinition.java";

    /** Comments and text blocks (skipped), char literals, and string literals (group 1 = content). */
    private static final Pattern JAVA_TOKENS = Pattern.compile(
            "//[^\\n]*|/\\*[\\s\\S]*?\\*/|\"\"\"[\\s\\S]*?\"\"\"|'(?:\\\\.|[^'\\\\])*'|\"((?:\\\\.|[^\"\\\\\\n])*)\"");

    private static Map<String, Path> executorSources;

    private ExecutorConfigReads() {
    }

    /** The block types the executors support, sorted. */
    public static Set<String> types() {
        return executorSources().keySet();
    }

    /**
     * Whether the executor of {@code type} resolves provider/model/effort itself — calls
     * {@code providerFor}, {@code modelFor} or {@code effortFor} outside comments.
     */
    public static boolean resolvesAiSettings(String type) throws IOException {
        return RESOLVES_AI_SETTINGS.matcher(withoutComments(Files.readString(executorSource(type)))).find();
    }

    /** Adds a problem for every key none of the sources of {@code type} reads. */
    static void reportUnread(String type, String where, Collection<String> keys, List<String> problems)
            throws IOException {
        Set<String> literals = literals(type);
        List<String> names = sourcesOf(type).stream().map(p -> p.getFileName().toString()).toList();
        keys.stream().filter(key -> !literals.contains(key))
                .forEach(key -> problems.add(where + " names `" + key + "`, but none of " + names + " reads it"));
    }

    /** The string literals in the code that runs {@code type}. */
    static Set<String> literals(String type) throws IOException {
        Set<String> literals = new HashSet<>();
        for (Path source : sourcesOf(type)) {
            literals.addAll(stringLiterals(Files.readString(source)));
        }
        return literals;
    }

    static List<Path> sourcesOf(String type) throws IOException {
        Path executor = executorSource(type);
        List<Path> sources = new ArrayList<>(List.of(executor, MAIN.resolve(ENGINE)));
        if (resolvesAiSettings(type)) {
            sources.add(MAIN.resolve(AI_SETTINGS));
        }
        if (CHECKS_EXECUTION_MODE.matcher(withoutComments(Files.readString(executor))).find()) {
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

    private static Path executorSource(String type) {
        Path executor = executorSources().get(type);
        assertThat(executor).as("source of the BlockExecutor for '%s'", type).isNotNull().exists();
        return executor;
    }

    static Set<String> stringLiterals(String javaSource) {
        Set<String> literals = new HashSet<>();
        Matcher token = JAVA_TOKENS.matcher(javaSource);
        while (token.find()) {
            if (token.group(1) != null) {
                literals.add(token.group(1));
            }
        }
        return literals;
    }

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

    private static String withoutComments(String javaSource) {
        return JAVA_TOKENS.matcher(javaSource).replaceAll(token ->
                token.group().startsWith("/") ? " " : Matcher.quoteReplacement(token.group()));
    }
}
