package com.automation.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shared utilities for host process execution:
 * <ul>
 *   <li>{@link #getHostUid()} — cached UID lookup with config override and fallback</li>
 *   <li>{@link #drain(Process, int, String)} — robust stdout drain with timeout</li>
 *   <li>{@link #isOnPath(String)} — is this CLI installed at all, without starting it</li>
 * </ul>
 */
@Component
@Slf4j
public class HostProcessUtils {

    @Value("${automation.cli.host-uid:#{null}}")
    private String configuredHostUid;

    private volatile String cachedHostUid;

    /**
     * Returns the host UID for Docker volume/user mapping.
     * Priority: configured value → cached process result → {@code id -u} → {@code "1000"}.
     */
    public String getHostUid() {
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

    /**
     * Drains process stdout, waits for completion with timeout, and ensures all streams
     * are closed — even on timeout or interruption.
     *
     * @param proc           the process whose stdout to read
     * @param timeoutSeconds wall-clock timeout for process completion
     * @param label          human-readable label used in exception messages
     * @return trimmed stdout string
     * @throws IOException on timeout, interruption, or stream failure
     */
    public String drain(Process proc, int timeoutSeconds, String label) throws IOException {
        return ProcessDrain.drain(proc, timeoutSeconds, label, LineListener.NONE);
    }

    /**
     * Whether {@code command} resolves to an executable file on this JVM's {@code PATH} — the same
     * lookup {@link ProcessBuilder} does for a bare command name (it searches the parent's PATH, not
     * the child environment's), so {@code false} means the process could not be started at all.
     *
     * <p>No process is started, no file is read: a handful of {@code stat} calls. Nothing is cached
     * here either — a caller on a request path caches the answer itself (the model catalog does, per
     * its own TTL).
     *
     * <p>It is a <b>necessary, not a sufficient</b> condition: an installed CLI can still be
     * unauthenticated, out of quota or broken. Only calling it would tell, and that costs a call.
     */
    public boolean isOnPath(String command) {
        return resolvesOnPath(command, System.getenv("PATH"), System.getenv("PATHEXT"));
    }

    /**
     * The pure lookup behind {@link #isOnPath(String)}, with the environment handed in so a test
     * does not depend on the machine it runs on.
     *
     * @param pathExtensions value of {@code PATHEXT}; it only exists on Windows, and {@code null}
     *                       (every other platform) simply means "the name as it is"
     */
    static boolean resolvesOnPath(String command, String pathVariable, String pathExtensions) {
        if (command == null || command.isBlank()) {
            return false;
        }
        if (command.indexOf('/') >= 0 || command.indexOf(File.separatorChar) >= 0) {
            return isExecutableFile(null, command); // a name with a separator is a path, as in execvp
        }
        List<String> names = new ArrayList<>();
        names.add(command);
        if (pathExtensions != null) {
            for (String extension : pathExtensions.split(File.pathSeparator)) {
                if (!extension.isBlank()) {
                    names.add(command + extension.strip());
                }
            }
        }
        for (String entry : (pathVariable == null ? "" : pathVariable).split(File.pathSeparator, -1)) {
            for (String name : names) {
                // An empty PATH entry means the working directory — again as in execvp.
                if (isExecutableFile(entry.isEmpty() ? "." : entry, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A broken PATH entry (unmappable characters, a NUL byte) is simply not a hit, never a crash. */
    private static boolean isExecutableFile(String directory, String name) {
        try {
            Path candidate = directory == null ? Path.of(name) : Path.of(directory, name);
            return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
        } catch (RuntimeException notAUsablePath) {
            return false;
        }
    }
}
