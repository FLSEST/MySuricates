package com.automation.service.staging;

import com.automation.config.DataHome;
import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedStateException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared Docker container utilities for staging and testing environments:
 * <ul>
 *   <li>{@link #isRunning(String)} — check if a named container is up</li>
 *   <li>{@link #containerExists(String)} — check if a named container exists, running or not</li>
 *   <li>{@link #isHealthy(String)} — HTTP health check</li>
 *   <li>{@link #runCommand(String, Path, Map, String...)} — run a command with output logging</li>
 *   <li>{@link #readApiKey(String)} — the API key a container wrote into its data folder</li>
 * </ul>
 */
@Component
@Slf4j
public class DockerContainerManager {

    /**
     * Returns {@code true} if a Docker container matching {@code containerName} is currently running.
     */
    public boolean isRunning(String containerName) {
        return !dockerPs(containerName, "--filter", "name=" + containerName, "--format", "{{.Status}}").isEmpty();
    }

    /**
     * Returns {@code true} if a container named exactly {@code containerName} exists in any state
     * (running, restarting, exited). {@code docker compose down} removes it; a crash leaves it behind.
     */
    public boolean containerExists(String containerName) {
        return dockerPs(containerName, "-a", "--filter", "name=^" + containerName + "$", "--format", "{{.Names}}")
                .lines().anyMatch(name -> name.equals(containerName));
    }

    /** Runs {@code docker ps <args>}; empty when the name is blank or docker is missing, unreachable or fails. */
    private String dockerPs(String containerName, String... args) {
        if (containerName == null || containerName.isBlank()) {
            return "";
        }

        try {
            List<String> command = new ArrayList<>(List.of("docker", "ps"));
            command.addAll(List.of(args));
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                log.debug("Container status check failed for '{}' (exit {}): {}", containerName, exitCode, output);
                return "";
            }
            return output;
        } catch (Exception e) {
            log.debug("Container status check failed for '{}': {}", containerName, e.getMessage());
            return "";
        }
    }

    /**
     * The key file in the container's data folder — the same rule as {@code DataHome} and
     * {@code scripts/lib/data-home.sh}: {@code $HOME/.autotrigger} while it exists, else
     * {@code $HOME/.mysuricates}. Exit 3 and the path when the file is missing or empty.
     */
    static final String KEY_FILE_SH = "if [ -d \"$HOME/.autotrigger\" ]; then f=\"$HOME/.autotrigger/api-key\"; "
            + "else f=\"$HOME/.mysuricates/api-key\"; fi; [ -s \"$f\" ] || { printf '%s' \"$f\"; exit 3; }; cat \"$f\"";

    /** Output and exit code of a short command. */
    record CommandResult(int exitCode, String output) {
    }

    /**
     * The API key of a Stable/Test container, read from the key file the instance writes into its data
     * folder at every start ({@code ApiKeyService}) — like {@code scripts/instance.sh key}, never from the
     * instance's database.
     *
     * @throws LocalizedStateException {@code CONTAINER_API_KEY_MISSING} when the file is not there (the
     *                                 container was started by a build from before the file), else
     *                                 {@code CONTAINER_API_KEY_UNREADABLE} (container not running, no docker …)
     */
    public String readApiKey(String containerName) {
        CommandResult result = capture("docker", "exec", containerName, "sh", "-c", KEY_FILE_SH);
        String output = result.output().trim();
        if (result.exitCode() == 0 && !output.isEmpty()) {
            return output;
        }
        if (result.exitCode() == 3) {
            throw new LocalizedStateException(ErrorMessage.CONTAINER_API_KEY_MISSING,
                    Map.of("container", containerName, "file", output));
        }
        throw new LocalizedStateException(ErrorMessage.CONTAINER_API_KEY_UNREADABLE,
                Map.of("container", containerName, "error", output.isEmpty() ? "exit " + result.exitCode() : output));
    }

    /** Runs a short command and returns its output; exit -1 and the message when it cannot start. */
    CommandResult capture(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            return new CommandResult(p.waitFor(), output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "interrupted");
        } catch (Exception e) {
            return new CommandResult(-1, String.valueOf(e.getMessage()));
        }
    }

    /**
     * Returns {@code true} if a GET to {@code healthUrl} responds with HTTP 200.
     */
    public boolean isHealthy(String healthUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(healthUrl).toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            log.debug("Health check failed for {}: {}", healthUrl, e.getMessage());
            return false;
        }
    }

    /**
     * Runs a command synchronously, logging each output line with {@code logPrefix}.
     * Throws {@link RuntimeException} if the process exits non-zero.
     */
    public void runCommand(String logPrefix, Path workDir, String... command) {
        runCommand(logPrefix, workDir, Map.of(), command);
    }

    /**
     * Runs a command synchronously with optional extra environment variables,
     * logging each output line with {@code logPrefix}.
     * Throws {@link RuntimeException} if the process exits non-zero.
     */
    public void runCommand(String logPrefix, Path workDir, Map<String, String> extraEnv, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            if (!extraEnv.isEmpty()) {
                pb.environment().putAll(extraEnv);
            }
            DataHome.export(pb.environment());
            Process p = pb.start();

            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[{}] {}", logPrefix, line);
                }
            }

            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Command failed (exit " + exitCode + "): " + String.join(" ", command));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Command interrupted", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Command execution failed: " + e.getMessage(), e);
        }
    }
}
