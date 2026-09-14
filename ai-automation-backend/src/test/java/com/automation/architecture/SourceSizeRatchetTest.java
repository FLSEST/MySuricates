package com.automation.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet against God Classes: no production source file may exceed {@link #MAX_LINES} lines
 * unless it is listed in {@link #ALLOWLIST} with its current size as ceiling.
 *
 * <p>The list only ever shrinks: a listed file may get smaller (update the ceiling when you
 * touch it), but it must not grow past its ceiling, and once it drops to or below the limit
 * its entry must be removed. New files above the limit fail the build — split them instead
 * of extending the list (Audit-TODO 1e248ab9, rule "keine neue Datei >300 LOC ohne Split-Plan").
 * The single tolerated exception: a ceiling may rise by the lines a split of <em>another</em>
 * class forces into a caller (one constructor field per injected collaborator) — never for
 * new logic. Say so in the commit message.
 *
 * <p>Line counting is {@code Files.readAllLines(..).size()} – identical to
 * {@code len(open(f).read().splitlines())} in Python, and to {@code wc -l} for files that end
 * with a newline.
 */
class SourceSizeRatchetTest {

    static final int MAX_LINES = 300;

    /** Relative to src/main/java, forward slashes. Value = ceiling (current size when listed). */
    static final Map<String, Integer> ALLOWLIST = Map.ofEntries(
            entry("com/automation/service/ai/HostClaudeService.java", 479),
            entry("com/automation/service/ai/HostPiService.java", 337),
            entry("com/automation/service/assistant/AssistantActionSuggestionService.java", 427),
            entry("com/automation/service/cli/CommandExecutorService.java", 365),
            entry("com/automation/service/cli/ContainerShellService.java", 350),
            entry("com/automation/service/dialog/DialogConversionService.java", 329),
            entry("com/automation/service/dialog/DialogSessionScanner.java", 348),
            entry("com/automation/service/factory/WorkflowFactoryService.java", 317),
            entry("com/automation/service/integration/providers/confluence/ConfluenceProvider.java", 315),
            entry("com/automation/service/integration/providers/google/GoogleCalendarProvider.java", 372),
            entry("com/automation/service/integration/providers/google/GoogleGmailProvider.java", 330),
            entry("com/automation/service/learning/LearningService.java", 456),
            entry("com/automation/service/repair/AutoRepairService.java", 394),
            entry("com/automation/service/staging/InstanceSyncHelper.java", 424),
            entry("com/automation/service/staging/StagingService.java", 428),
            entry("com/automation/service/staging/TestingService.java", 450),
            entry("com/automation/service/tray/SystemTrayService.java", 415),
            entry("com/automation/service/trigger/TodoTriggerService.java", 383),
            entry("com/automation/service/wish/WishDecomposePromptBuilder.java", 338),
            entry("com/automation/service/workflow/WorkflowExecutor.java", 540)
    );

    @Test
    void noProductionSourceGrowsPastTheRatchet() throws IOException {
        Path root = sourceRoot();
        Map<String, Integer> sizes = measure(root);

        List<String> problems = new ArrayList<>();
        sizes.forEach((file, lines) -> {
            Integer ceiling = ALLOWLIST.get(file);
            if (ceiling == null) {
                if (lines > MAX_LINES) {
                    problems.add(file + " has " + lines + " lines (> " + MAX_LINES
                            + "). Split it – do not extend the allowlist.");
                }
            } else if (lines > ceiling) {
                problems.add(file + " grew to " + lines + " lines (ceiling " + ceiling + "). Shrink it.");
            } else if (lines <= MAX_LINES) {
                problems.add(file + " is down to " + lines + " lines – remove it from the allowlist.");
            }
        });
        ALLOWLIST.keySet().stream()
                .filter(file -> !sizes.containsKey(file))
                .sorted()
                .forEach(file -> problems.add(file + " no longer exists – remove it from the allowlist."));

        assertThat(problems).as("Source size ratchet (max %d lines)", MAX_LINES).isEmpty();
    }

    @Test
    void allowlistEntriesAreAboveTheLimit() {
        ALLOWLIST.forEach((file, ceiling) ->
                assertThat(ceiling).as("ceiling of %s", file).isGreaterThan(MAX_LINES));
    }

    /** Maven runs with user.dir = module dir; fall back to the repo-root layout for IDE runners. */
    private static Path sourceRoot() {
        Path module = Paths.get("src/main/java");
        if (Files.isDirectory(module)) return module;
        Path fromRepoRoot = Paths.get("ai-automation-backend/src/main/java");
        assertThat(fromRepoRoot).as("source root not found from " + Paths.get("").toAbsolutePath()).isDirectory();
        return fromRepoRoot;
    }

    private static Map<String, Integer> measure(Path root) throws IOException {
        Map<String, Integer> sizes = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                sizes.put(rel, Files.readAllLines(p, StandardCharsets.UTF_8).size());
            }
        }
        return new LinkedHashMap<>(sizes);
    }
}
