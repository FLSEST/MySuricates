package com.automation.service.staging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the {@code docker compose} command line for Stable and Test together with the override file
 * that decides what comes from the host: the AI providers (how much of their setup, which ones), the
 * Docker socket (off unless {@code STABLE_/TEST_DOCKER_SOCKET=1}) and the published port (only on
 * {@code 127.0.0.1} unless {@code STABLE_/TEST_BIND} opens it). The override is written by {@code scripts/staging.sh write-override stable|test},
 * i.e. by the same generator ({@code scripts/lib/host-mounts.sh}) that the script itself,
 * {@code instance.sh} and the dev container use — a start from the UI gets exactly the mounts of a
 * start from the shell (262948a4).
 *
 * <p>Without an override the container would start without any AI provider and without a published
 * port, which looks like a broken instance. So a missing script or a failed write is an error, not a
 * silent fallback.
 */
@Component
@Slf4j
public class StagingComposeCommand {

    /** Runs a command and returns its combined output; throws when the exit code is not 0. */
    @FunctionalInterface
    interface OutputRunner {
        String run(Path workDir, List<String> command) throws IOException, InterruptedException;
    }

    private final OutputRunner runner;

    public StagingComposeCommand() {
        this(StagingComposeCommand::runProcess);
    }

    StagingComposeCommand(OutputRunner runner) {
        this.runner = runner;
    }

    /**
     * @param backendDir  {@code ai-automation-backend/} of the checkout; the script is next to it
     * @param composeFile {@code docker-compose.stable.yml} or {@code docker-compose.test.yml}
     * @param environment {@code stable} or {@code test}
     * @param composeArgs the compose subcommand, e.g. {@code up -d}
     */
    public String[] command(Path backendDir, Path composeFile, String environment, String... composeArgs) {
        Path override = writeOverride(backendDir, environment);
        List<String> command = new ArrayList<>(List.of(
                "docker", "compose", "-f", composeFile.toString(), "-f", override.toString()));
        command.addAll(Arrays.asList(composeArgs));
        return command.toArray(String[]::new);
    }

    Path writeOverride(Path backendDir, String environment) {
        Path script = backendDir.toAbsolutePath().normalize().resolveSibling("scripts").resolve("staging.sh");
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("scripts/staging.sh not found at " + script
                    + " — cannot write the compose override for " + environment);
        }
        String output;
        try {
            output = runner.run(backendDir, List.of("bash", script.toString(), "write-override", environment));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Writing the compose override was interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Writing the compose override for " + environment + " failed: "
                    + e.getMessage(), e);
        }
        // Warnings about providers this instance cannot use come first, the path is the last line.
        List<String> lines = output.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        lines.stream().limit(Math.max(0, lines.size() - 1)).forEach(l -> log.warn("[staging:{}] {}", environment, l));
        Path override = lines.isEmpty() ? null : Path.of(lines.get(lines.size() - 1));
        if (override == null || !Files.isRegularFile(override)) {
            throw new IllegalStateException("scripts/staging.sh write-override " + environment
                    + " named no override file: " + output.strip());
        }
        return override;
    }

    private static String runProcess(Path workDir, List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("exit " + exit + ": " + out.toString().strip());
        }
        return out.toString();
    }
}
