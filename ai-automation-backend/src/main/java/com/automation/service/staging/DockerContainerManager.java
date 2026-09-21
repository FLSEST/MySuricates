package com.automation.service.staging;

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
