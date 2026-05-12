package com.automation.service.project;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Validates host-side paths that a project (and therefore a Docker container
 * mount) may legitimately point at. Two modes:
 *
 * <ul>
 *   <li><b>Allowlist mode</b> — when {@code automation.workspace.root} is set,
 *       the only legal paths are those under that root. This is the recommended
 *       configuration for any shared deployment.</li>
 *   <li><b>Blocklist mode</b> — fallback when no workspace root is configured;
 *       rejects well-known system directories and sensitive user-home subpaths.
 *       Less safe but matches a single-user local install.</li>
 * </ul>
 *
 * Both modes normalize {@code ..} segments before deciding, so traversal
 * payloads like {@code /home/me/../../etc} are caught.
 */
@Component
@Slf4j
public class PathValidator {

    /** Directories that always contain OS state, never user data. */
    private static final List<String> SYSTEM_DIRECTORIES = List.of(
            "/etc", "/proc", "/sys", "/dev",
            "/var/run", "/var/lib", "/var/log", "/var/cache",
            "/usr", "/boot", "/root", "/run", "/lost+found"
    );

    /**
     * Home-relative subpaths that contain secrets and must never be mounted
     * into a container as a workspace. Checked against any home directory,
     * not just the current user's, to cover {@code /home/otheruser/.ssh}.
     */
    private static final Set<String> SENSITIVE_HOME_SUBPATHS = Set.of(
            ".ssh", ".gnupg", ".aws", ".kube", ".docker", ".config/gcloud",
            ".azure", ".netrc", ".password-store", ".ansible/vault_pass"
    );

    @Value("${automation.workspace.root:}")
    private String workspaceRoot;

    /**
     * Project-create / project-update validation. Looser than the
     * container-mount check — accepts any path under the workspace root or, in
     * blocklist mode, any path that is not an obvious system directory.
     */
    public void validate(String path) {
        Path normalized = normalize(path);
        String normalizedStr = normalized.toString();

        if (workspaceRoot != null && !workspaceRoot.isBlank()) {
            Path root = Path.of(workspaceRoot).normalize().toAbsolutePath();
            if (!normalized.startsWith(root)) {
                throw new IllegalArgumentException("Path must be under workspace root: " + root);
            }
        } else {
            if (normalizedStr.equals("/")) {
                throw new IllegalArgumentException("Path points to a restricted system directory: " + path);
            }
            for (String restricted : SYSTEM_DIRECTORIES) {
                if (normalizedStr.equals(restricted) || normalizedStr.startsWith(restricted + "/")) {
                    throw new IllegalArgumentException("Path points to a restricted system directory: " + path);
                }
            }
        }

        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Path must be an existing directory: " + path);
        }
    }

    /**
     * Defense-in-depth check for container mounts. The sensitive-subpath check
     * runs before the {@link #validate} filesystem check so callers (and tests)
     * see the security reason, not a "path does not exist" message, when an
     * attacker points the project path at a sensitive location that happens to
     * not exist on the current host.
     */
    public void validateForContainerMount(String path) {
        if (isSensitiveHomeSubpath(path)) {
            throw new IllegalArgumentException(
                    "Path points to a sensitive home subpath and cannot be mounted: " + path);
        }
        validate(path);
    }

    /**
     * Package-private for testing. True if the normalized path equals or sits
     * under {@code /home/<anyUser>/<sensitive>} or {@code /root/<sensitive>},
     * for any entry in {@link #SENSITIVE_HOME_SUBPATHS}. Pure pattern matching,
     * no filesystem access.
     */
    boolean isSensitiveHomeSubpath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String s = Path.of(path).normalize().toAbsolutePath().toString();
        for (String subpath : SENSITIVE_HOME_SUBPATHS) {
            if (s.startsWith("/home/")) {
                int afterUser = s.indexOf('/', "/home/".length());
                if (afterUser > 0) {
                    String tail = s.substring(afterUser + 1);
                    if (tail.equals(subpath) || tail.startsWith(subpath + "/")) {
                        return true;
                    }
                }
            }
            if (s.equals("/root/" + subpath) || s.startsWith("/root/" + subpath + "/")) {
                return true;
            }
        }
        return false;
    }

    private static Path normalize(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }
        return Path.of(path).normalize().toAbsolutePath();
    }
}
