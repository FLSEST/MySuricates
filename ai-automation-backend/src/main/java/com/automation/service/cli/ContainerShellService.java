package com.automation.service.cli;

import com.automation.service.cli.ShellCommandService.ShellResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executes shell commands in ephemeral Docker containers via "docker run --rm".
 * Same ShellResult return type as ShellCommandService for easy interchangeability.
 */
@Service
@Slf4j
public class ContainerShellService {

    @Value("${automation.cli.host-uid:#{null}}")
    private String configuredHostUid;

    private volatile String cachedHostUid;

    /**
     * Execute a command inside an ephemeral container.
     *
     * @param command            Shell command to run
     * @param projectPath        Host path to mount as /workspace
     * @param timeoutSeconds     Timeout for the entire docker run
     * @param containerImage     Docker image name (e.g. "automation-agent-devops:latest")
     * @param mountDockerSocket  Whether to mount /var/run/docker.sock
     */
    public ShellResult execute(String command, String projectPath, int timeoutSeconds,
                               String containerImage, boolean mountDockerSocket) {
        long start = System.currentTimeMillis();

        List<String> dockerCmd = buildDockerCommand(command, projectPath, containerImage, mountDockerSocket);

        log.debug("Container shell execute (image: {}, workDir: {}, timeout: {}s): {}",
                containerImage, projectPath, timeoutSeconds,
                command.length() > 200 ? command.substring(0, 200) + "..." : command);

        try {
            ProcessBuilder pb = new ProcessBuilder(dockerCmd);
            pb.redirectErrorStream(true);

            Process proc = pb.start();

            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
            Thread reader = Thread.ofVirtual().start(() -> {
                try (InputStream is = proc.getInputStream()) {
                    is.transferTo(outputBuffer);
                } catch (IOException ignored) {
                    // Process destroyed — expected on timeout
                }
            });

            boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                proc.destroyForcibly();
                proc.waitFor(2, TimeUnit.SECONDS);
                reader.join(2000);
                long durationMs = System.currentTimeMillis() - start;
                log.warn("Container command timed out after {}s (image: {})", timeoutSeconds, containerImage);
                return new ShellResult("Command timed out after " + timeoutSeconds + " seconds", 124, durationMs);
            }

            reader.join(5000);
            String output = outputBuffer.toString().trim();
            int exitCode = proc.exitValue();
            long durationMs = System.currentTimeMillis() - start;

            if (exitCode != 0) {
                log.debug("Container command exited with code {} (image: {})", exitCode, containerImage);
            }

            return new ShellResult(output, exitCode, durationMs);
        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("Container command failed: {}", e.getMessage());
            return new ShellResult("Error: " + e.getMessage(), 1, durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = System.currentTimeMillis() - start;
            return new ShellResult("Command interrupted", 130, durationMs);
        }
    }

    private List<String> buildDockerCommand(String command, String projectPath,
                                            String containerImage, boolean mountDockerSocket) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");

        // Run as host user to match file ownership
        cmd.add("--user");
        cmd.add(getHostUid());

        cmd.add("--workdir");
        cmd.add("/workspace");

        // Mount project directory
        if (projectPath != null && !projectPath.isBlank()) {
            cmd.add("-v");
            cmd.add(projectPath + ":/workspace:rw");
        }

        // Optionally mount Docker socket for docker/trivy commands
        if (mountDockerSocket) {
            cmd.add("-v");
            cmd.add("/var/run/docker.sock:/var/run/docker.sock");
        }

        // Security hardening
        cmd.add("--cap-drop");
        cmd.add("ALL");
        cmd.add("--security-opt");
        cmd.add("no-new-privileges");

        // Resource limits
        cmd.add("--memory");
        cmd.add("2g");
        cmd.add("--pids-limit");
        cmd.add("100");

        cmd.add(containerImage);
        cmd.add("/bin/bash");
        cmd.add("-c");
        cmd.add(command);

        return cmd;
    }

    private String getHostUid() {
        if (configuredHostUid != null && !configuredHostUid.isBlank()) {
            return configuredHostUid;
        }
        if (cachedHostUid != null) {
            return cachedHostUid;
        }
        try {
            Process p = new ProcessBuilder("id", "-u").start();
            String uid = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(5, TimeUnit.SECONDS);
            cachedHostUid = uid;
            return uid;
        } catch (Exception e) {
            log.warn("Could not determine host UID, defaulting to 1000");
            cachedHostUid = "1000";
            return "1000";
        }
    }
}
