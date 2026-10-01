package com.automation.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate: every class that starts a host process either strips the backend's own secrets from the
 * child's environment ({@code ChildProcessEnv.scrub}) or is pinned here with the reason it may
 * keep them. A {@link ProcessBuilder} inherits the backend environment, which in every Docker
 * setup carries {@code AUTOMATION_API_KEY} — a new spawn site for AI CLIs or workflow-controlled
 * commands must not hand it on (TODO 62037bf2).
 */
class ChildProcessEnvGateTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** The helper builds scrubbed process builders itself ({@code forAgent}). */
    private static final String HELPER = "com/automation/util/ChildProcessEnv.java";

    /** Must scrub: they run prompts, shell blocks or integration commands with workflow content. */
    private static final Set<String> MUST_SCRUB = Set.of(
            "com/automation/service/ai/HostClaudeService.java",
            "com/automation/service/ai/HostCodexService.java",
            "com/automation/service/ai/HostPiService.java",
            "com/automation/service/cli/ShellCommandService.java",
            "com/automation/service/cli/TaskCommandService.java");

    /** file → why it may start processes with the inherited environment. */
    private static final Map<String, String> EXEMPT = Map.ofEntries(
            // fixed probe commands (--list-models, version), no prompt and no workflow content
            Map.entry("com/automation/service/ai/catalog/HostCliCommandRunner.java", "fixed probe commands"),
            // docker exec / docker run: the container gets no host environment (no -e/--env flags)
            Map.entry("com/automation/service/cli/CliSession.java", "docker exec, no env passed"),
            Map.entry("com/automation/service/cli/ContainerCommandProcess.java", "docker run, no env passed"),
            Map.entry("com/automation/service/staging/DockerContainerManager.java", "docker CLI, fixed commands"),
            // staging/test instances get the dev key on purpose (StagingService/TestingService env)
            Map.entry("com/automation/service/staging/StagingComposeCommand.java", "compose for stable/test"),
            Map.entry("com/automation/service/staging/StagingService.java", "passes the key on purpose"),
            Map.entry("com/automation/service/staging/TestingService.java", "passes the key on purpose"),
            Map.entry("com/automation/service/system/FileBrowserService.java", "xdg-open on user action"),
            Map.entry("com/automation/service/system/HostTailscaleSelfQuery.java", "tailscale status"),
            Map.entry("com/automation/service/system/TailscaleService.java", "tailscale CLI"),
            Map.entry("com/automation/service/workflow/RunWorkspaceService.java", "git clone/worktree"),
            Map.entry("com/automation/util/HostProcessUtils.java", "id -u"));

    @Test
    void everyProcessSpawnSiteScrubsOrIsPinned() throws IOException {
        Set<String> spawning = new TreeSet<>();
        Set<String> scrubbing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                String name = MAIN.relativize(file).toString().replace('\\', '/');
                if (source.contains("new ProcessBuilder(") || source.contains("Runtime.getRuntime().exec(")) {
                    spawning.add(name);
                }
                if (source.contains("ChildProcessEnv.scrub(") || source.contains("ChildProcessEnv.forAgent(")) {
                    scrubbing.add(name);
                }
            }
        }

        assertThat(scrubbing)
                .as("these spawn sites run workflow content and must strip the backend secrets")
                .containsAll(MUST_SCRUB);
        Set<String> undecided = new TreeSet<>(spawning);
        undecided.removeAll(scrubbing);
        undecided.removeAll(EXEMPT.keySet());
        undecided.remove(HELPER);
        assertThat(undecided)
                .as("new host process spawn site: call ChildProcessEnv.scrub(pb) or pin it in EXEMPT with a reason")
                .isEmpty();
        assertThat(spawning)
                .as("stale EXEMPT entry — the file no longer starts processes")
                .containsAll(EXEMPT.keySet());
    }
}
