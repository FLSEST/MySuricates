package com.automation.service.cli;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.cli.ShellCommandService.ShellResult;
import com.automation.util.HostProcessUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Executes shell commands in ephemeral Docker containers via "docker run --rm".
 * Same ShellResult return type as ShellCommandService for easy interchangeability.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContainerShellService {

    /**
     * Mount paths that are always rejected (regardless of allow-extra-mounts).
     * Matched against both host- and container-side of each bind-mount: a segment
     * is rejected if it equals one of these or starts with "<path>/" — so e.g.
     * "/etc/passwd" is rejected because it's a child of "/etc".
     */
    static final Set<String> FORBIDDEN_MOUNT_PATHS = Set.of(
            "/var/run/docker.sock",
            "/",
            "/etc",
            "/root",
            "/proc",
            "/sys"
    );

    private final HostProcessUtils hostProcessUtils;
    private final ActivityLogService activityLogService;

    @Value("${automation.execution.allow-docker-socket-mount:false}")
    private boolean allowDockerSocketMount;

    @Value("${automation.execution.allow-extra-mounts:false}")
    private volatile boolean allowExtraMounts;

    /** Runtime-mutable setter used by ExecutionSettingsService for live config updates. */
    public void setAllowExtraMounts(boolean allow) {
        this.allowExtraMounts = allow;
    }

    public boolean isAllowExtraMounts() {
        return allowExtraMounts;
    }

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
        return execute(command, projectPath, timeoutSeconds, containerImage, mountDockerSocket, List.of());
    }

    /**
     * Execute a command inside an ephemeral container with additional bind-mounts.
     *
     * @param extraMounts  Extra bind-mounts in docker-volume syntax "host:container[:ro|rw]".
     *                     Template variables must already be resolved by the caller.
     */
    public ShellResult execute(String command, String projectPath, int timeoutSeconds,
                               String containerImage, boolean mountDockerSocket,
                               List<String> extraMounts) {
        long start = System.currentTimeMillis();

        boolean effectiveMount = mountDockerSocket && allowDockerSocketMount;
        if (mountDockerSocket && !allowDockerSocketMount) {
            log.warn("Docker socket mount requested but denied by server policy (automation.execution.allow-docker-socket-mount=false)");
            activityLogService.warn(LogCategory.SECURITY,
                    "Docker socket mount denied by server policy for image: " + containerImage,
                    "ContainerShellService", null);
        }
        if (effectiveMount) {
            activityLogService.warn(LogCategory.SECURITY,
                    "Docker socket mounted for container execution (image: " + containerImage + ")",
                    "ContainerShellService", null);
        }

        List<String> safeExtraMounts = filterExtraMounts(extraMounts, containerImage);

        List<String> dockerCmd = buildDockerCommand(command, projectPath, containerImage, effectiveMount, safeExtraMounts);

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

    /**
     * Validates extra bind-mounts against the global allow-extra-mounts policy
     * and the forbidden-targets blacklist. Rejected entries are dropped from the
     * docker command and logged to the SECURITY ActivityLog.
     */
    List<String> filterExtraMounts(List<String> extraMounts, String containerImage) {
        if (extraMounts == null || extraMounts.isEmpty()) {
            return List.of();
        }
        List<String> safe = new ArrayList<>(extraMounts.size());
        for (String mount : extraMounts) {
            if (mount == null || mount.isBlank()) continue;

            if (!allowExtraMounts) {
                log.warn("Extra mount '{}' denied: allow-extra-mounts=false (image: {})", mount, containerImage);
                activityLogService.warn(LogCategory.SECURITY,
                        "Extra mount denied (allow-extra-mounts=false): " + mount + " (image: " + containerImage + ")",
                        "ContainerShellService", null);
                continue;
            }

            String forbidden = findForbiddenSegment(mount);
            if (forbidden != null) {
                log.warn("Extra mount '{}' rejected: matches forbidden path '{}' (image: {})", mount, forbidden, containerImage);
                activityLogService.warn(LogCategory.SECURITY,
                        "Extra mount rejected (forbidden path " + forbidden + "): " + mount + " (image: " + containerImage + ")",
                        "ContainerShellService", null);
                continue;
            }

            activityLogService.warn(LogCategory.SECURITY,
                    "Extra mount activated: " + mount + " (image: " + containerImage + ")",
                    "ContainerShellService", null);
            safe.add(mount);
        }
        return safe;
    }

    /**
     * Returns the forbidden path that matches either side of the bind-mount, or
     * null if the mount is acceptable. A path is considered "matched" when a
     * mount segment equals it or is a child of it (e.g. "/etc/foo" matches "/etc").
     */
    static String findForbiddenSegment(String mount) {
        // Docker bind-mount syntax: "host:container[:options]"
        String[] parts = mount.split(":", 3);
        if (parts.length < 2) return null;
        String host = parts[0].trim();
        String container = parts[1].trim();

        for (String forbidden : FORBIDDEN_MOUNT_PATHS) {
            if (matchesForbidden(host, forbidden) || matchesForbidden(container, forbidden)) {
                return forbidden;
            }
        }
        return null;
    }

    private static boolean matchesForbidden(String path, String forbidden) {
        if (path == null || path.isBlank()) return false;
        String normalized = path.endsWith("/") && !path.equals("/")
                ? path.substring(0, path.length() - 1)
                : path;
        if (normalized.equals(forbidden)) return true;
        if (forbidden.equals("/")) return false; // every path starts with "/" — only exact match counts
        return normalized.startsWith(forbidden + "/");
    }

    private List<String> buildDockerCommand(String command, String projectPath,
                                            String containerImage, boolean mountDockerSocket,
                                            List<String> extraMounts) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");

        // Run as host user to match file ownership
        cmd.add("--user");
        cmd.add(hostProcessUtils.getHostUid());

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

        // Extra bind-mounts (already validated by filterExtraMounts)
        if (extraMounts != null) {
            for (String mount : extraMounts) {
                cmd.add("-v");
                cmd.add(mount);
            }
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
}
