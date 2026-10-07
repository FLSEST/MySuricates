package com.automation.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Keeps the instance's secrets away from child processes that nobody watches (TODO 9525820a).
 *
 * <p>AI CLIs and shell blocks run on the host as the backend's own user. A prompt-injected agent in an
 * unattended run could otherwise read the API key file, the seal and integration keys or the database
 * and drive the whole REST API, forge seals or decrypt credentials. With the startup switch
 * {@code automation.hardening.unattended-sandbox} every such child starts behind {@code landlock_exec.py}
 * (Linux Landlock, no root needed), which hides those paths from it and everything it starts.
 *
 * <p>Only processes of an attended run (started by a person, {@link #attended}) start unchanged — like
 * with the switch off. Everything else is sandboxed: unattended runs, but also AI calls outside a run
 * (auto-memory, tagging, assistant), so that a path nobody marked fails safe. A host without Landlock
 * (macOS, an old kernel) cannot start such a process at all while the switch is on: the helper ends with
 * exit code 126 and says why, rather than running the command unprotected.
 *
 * <p>The helper is passed as {@code python3 -I -S -c <code>}: there is no copy on disk that a sandboxed
 * process could change before the next start.
 */
public final class ProcessSandbox {

    /** What the configuration decides: the switch, and the paths to hide, computed per start. */
    public record Policy(boolean enabled, Supplier<Collection<Path>> hiddenPaths) {
        public Policy {
            Objects.requireNonNull(hiddenPaths, "hiddenPaths");
        }
    }

    static final String HELPER_RESOURCE = "/sandbox/landlock_exec.py";
    static final String PYTHON = "python3";
    private static final Policy OFF = new Policy(false, List::of);
    private static final ThreadLocal<Boolean> ATTENDED = new ThreadLocal<>();
    private static volatile Policy policy = OFF;
    private static volatile String helper;

    private ProcessSandbox() {
    }

    /** Set once at startup by {@code ProcessSandboxConfiguration}; tests set and {@link #reset} it. */
    public static void configure(Policy newPolicy) {
        policy = Objects.requireNonNull(newPolicy, "policy");
    }

    static void reset() {
        policy = OFF;
    }

    /**
     * Runs {@code task} with this thread marked as working for an attended run (or not). The run thread
     * of a workflow sets this; everything it starts on the same thread inherits the decision.
     */
    public static Runnable attended(boolean attended, Runnable task) {
        return () -> {
            Boolean previous = ATTENDED.get();
            ATTENDED.set(attended);
            try {
                task.run();
            } finally {
                if (previous == null) {
                    ATTENDED.remove();
                } else {
                    ATTENDED.set(previous);
                }
            }
        };
    }

    /** Whether a process started from this thread now runs behind the sandbox. */
    public static boolean active() {
        return policy.enabled() && !Boolean.TRUE.equals(ATTENDED.get());
    }

    /** Puts the sandbox in front of the builder's command when {@link #active()}. */
    public static void apply(ProcessBuilder pb) {
        if (active()) {
            pb.command(wrap(pb.command(), policy.hiddenPaths().get()));
        }
    }

    /** {@code python3 -I -S -c <helper> --hide <path> … -- <command>}. */
    static List<String> wrap(List<String> command, Collection<Path> hidden) {
        if (hidden.isEmpty()) {
            throw new IllegalStateException("Process sandbox is on but knows no path to hide");
        }
        List<String> wrapped = new ArrayList<>(List.of(PYTHON, "-I", "-S", "-c", helper()));
        hidden.stream().map(p -> p.toAbsolutePath().normalize().toString()).distinct().forEach(path -> {
            wrapped.add("--hide");
            wrapped.add(path);
        });
        wrapped.add("--");
        wrapped.addAll(command);
        return wrapped;
    }

    static String helper() {
        String code = helper;
        if (code == null) {
            try (InputStream in = ProcessSandbox.class.getResourceAsStream(HELPER_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("Missing resource " + HELPER_RESOURCE);
                }
                code = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read " + HELPER_RESOURCE, e);
            }
            helper = code;
        }
        return code;
    }
}
