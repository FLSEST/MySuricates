package com.automation.architecture;

import com.automation.util.LocalizedJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Language variants of shipped definitions, for the gates (TODO a129a661).
 *
 * <p>A bilingual template or pool file ({@code "i18n": ["de", "en"]}) carries {@code {de, en}} maps
 * in its steps. A gate that reads a step field as a string would skip such a map silently and lose
 * coverage without anyone noticing. So the gates check every language variant, resolved with the
 * same {@link LocalizedJson} the loader and the pool use.
 */
public final class ShippedDefinitions {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One language variant of a file; {@code label} is {@code file.json} or {@code file.json[de]}. */
    public record Variant(String label, JsonNode root) {}

    /** Config fields that can be run through a shell, see {@link #isShellField}. */
    public static final List<String> SHELL_FIELDS =
            List.of("command", "condition", "then_command", "else_command", "items_command", "condition_command", "run_if");

    private ShippedDefinitions() {
    }

    /**
     * Whether {@code field} on a block of {@code type} (with {@code config}) is actually run
     * through a shell. {@code items_command} on {@code foreach} always is (it only ever collects
     * iteration targets, see {@code ForEachBlockExecutor}); every other shell field depends on the
     * block being type {@code shell} itself, or carrying {@code execution_mode: shell} (foreach,
     * while, conditional, retry, and a switch-nested shell/simple block — {@code ExecutionMode}).
     */
    public static boolean isShellField(String field, String type, JsonNode config) {
        if (!SHELL_FIELDS.contains(field)) {
            return false;
        }
        if ("items_command".equals(field)) {
            return "foreach".equals(type);
        }
        return "shell".equals(type) || "shell".equals(config.path("execution_mode").asText(null));
    }

    /**
     * The JSON files of every shipped template directory: {@code templates/builtin} (templates) and
     * {@code templates/blueprints} (blueprints, TODO 268ae0db). Works from the module and from the
     * repository root.
     */
    public static List<Path> templateFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String dir : List.of("builtin", "blueprints")) {
            for (Path candidate : List.of(Paths.get("src/main/resources/templates", dir),
                    Paths.get("ai-automation-backend/src/main/resources/templates", dir))) {
                if (Files.isDirectory(candidate)) {
                    try (Stream<Path> stream = Files.list(candidate)) {
                        files.addAll(stream.filter(p -> p.toString().endsWith(".json")).sorted().toList());
                    }
                    break;
                }
            }
        }
        return files;
    }

    /**
     * The {@code workflow-pool/} directory, seen from the backend module or the repository root;
     * {@code null} when neither is reachable.
     */
    public static Path poolDirectoryOrNull() {
        for (Path candidate : List.of(Paths.get("../workflow-pool"), Paths.get("workflow-pool"))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The pool files that are actually shipped: the JSON files git tracks directly in {@code pool}
     * (TODO ca858a23). Next to them a maintainer keeps private definitions, ignored via
     * {@code .git/info/exclude} — a gate that reads them fails locally on files nobody gets, while a
     * fresh worktree stays green. Without git (a source archive) every JSON file in the directory
     * counts — and so it does when git answers with no tracked JSON file at all: a gate that reads
     * nothing would pass silently.
     */
    public static List<Path> poolFiles(Path pool) throws IOException {
        Optional<List<Path>> tracked = gitTrackedNames(pool).map(names -> names.stream()
                .filter(name -> name.endsWith(".json"))
                .map(pool::resolve)
                .filter(Files::isRegularFile)
                .sorted()
                .toList());
        if (tracked.isPresent() && !tracked.get().isEmpty()) {
            return tracked.get();
        }
        try (Stream<Path> stream = Files.list(pool)) {
            return stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    /** Names git tracks directly in {@code dir}; empty when git is missing or {@code dir} is no repo. */
    private static Optional<List<String>> gitTrackedNames(Path dir) {
        try {
            Process git = new ProcessBuilder("git", "ls-files", "-z", "--", ".")
                    .directory(dir.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (git.waitFor() != 0) {
                return Optional.empty();
            }
            return Optional.of(Stream.of(out.split("\0"))
                    .filter(name -> !name.isEmpty() && !name.contains("/"))
                    .toList());
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** One variant per declared language, or the file itself when it has no {@code i18n} marker. */
    public static List<Variant> variants(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(file.toFile());
        String name = file.getFileName().toString();
        List<String> locales = LocalizedJson.declaredLocales(root);
        if (locales.isEmpty()) {
            return List.of(new Variant(name, root));
        }
        return locales.stream()
                .map(locale -> new Variant(name + "[" + locale + "]", LocalizedJson.resolveDefinition(root, locale)))
                .toList();
    }
}
