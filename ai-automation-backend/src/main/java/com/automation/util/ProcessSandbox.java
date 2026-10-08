package com.automation.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
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
 * <p>The helper is passed as {@code <python3> -I -S -c <code>}: there is no copy on disk that a sandboxed
 * process could change before the next start. The interpreter itself runs before Landlock, so it is never
 * looked up via {@code PATH} (where {@code ~/.local/bin} comes first on Ubuntu): {@link #trustedPython()}
 * fixes it once at startup to a file that only root can change (TODO 4c8a0755). Without such a file the
 * switched-on sandbox refuses to start these processes, like a host without Landlock.
 */
public final class ProcessSandbox {

    /**
     * What the configuration decides: the switch, the paths to hide (computed per start) and the python3 that
     * runs the helper ({@code null} when no {@linkplain #trustedPython() trusted one} exists).
     */
    public record Policy(boolean enabled, Supplier<Collection<Path>> hiddenPaths, Path interpreter) {
        public Policy {
            Objects.requireNonNull(hiddenPaths, "hiddenPaths");
        }
    }

    static final String HELPER_RESOURCE = "/sandbox/landlock_exec.py";
    /** Where python3 may come from — fixed locations only, never {@code PATH}. */
    static final List<Path> PYTHON_CANDIDATES = List.of(
            Path.of("/usr/bin/python3"), Path.of("/usr/local/bin/python3"), Path.of("/bin/python3"));
    private static final Policy OFF = new Policy(false, List::of, null);
    private static final ThreadLocal<Boolean> ATTENDED = new ThreadLocal<>();
    private static volatile Policy policy = OFF;
    private static volatile String helper;

    private ProcessSandbox() {
    }

    /** Set once at startup by {@code ProcessSandboxConfiguration}; tests set and {@link #reset} it. */
    public static void configure(Policy newPolicy) {
        policy = Objects.requireNonNull(newPolicy, "policy");
    }

    /** Switches the sandbox off again — for tests; {@link #configure} can do the same. */
    public static void reset() {
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

    /**
     * Whether this thread works for anything but an attended run — unmarked threads included. The container
     * mode ({@link UnattendedContainer}) decides with exactly this marker.
     */
    public static boolean unattendedHere() {
        return !Boolean.TRUE.equals(ATTENDED.get());
    }

    /** Whether a process started from this thread now runs behind the sandbox. */
    public static boolean active() {
        return active(policy);
    }

    private static boolean active(Policy current) {
        return current.enabled() && !Boolean.TRUE.equals(ATTENDED.get());
    }

    /** Puts the sandbox in front of the builder's command when {@link #active()}. */
    public static void apply(ProcessBuilder pb) {
        Policy current = policy;
        if (active(current)) {
            pb.command(wrap(current.interpreter(), pb.command(), current.hiddenPaths().get()));
        }
    }

    /** {@code <python3> -I -S -c <helper> --hide <path> … -- <command>}. */
    static List<String> wrap(Path interpreter, List<String> command, Collection<Path> hidden) {
        if (interpreter == null) {
            throw new IllegalStateException("Process sandbox is on but found no python3 that only root can change ("
                    + PYTHON_CANDIDATES + "): the process does not start unprotected");
        }
        if (hidden.isEmpty()) {
            throw new IllegalStateException("Process sandbox is on but knows no path to hide");
        }
        List<String> wrapped = new ArrayList<>(List.of(interpreter.toString(), "-I", "-S", "-c", helper()));
        hidden.stream().map(p -> p.toAbsolutePath().normalize().toString()).distinct().forEach(path -> {
            wrapped.add("--hide");
            wrapped.add(path);
        });
        wrapped.add("--");
        wrapped.addAll(command);
        return wrapped;
    }

    /**
     * The python3 to run the helper with: the first of {@link #PYTHON_CANDIDATES} whose real file (links
     * resolved) and every directory above it belong to root and are writable by nobody else. Returns that
     * real file, so a link changed later does not matter; empty when there is none.
     */
    public static Optional<Path> trustedPython() {
        return trustedInterpreter(PYTHON_CANDIDATES, ProcessSandbox::onlyRootCanChange);
    }

    static Optional<Path> trustedInterpreter(List<Path> candidates, Predicate<Path> onlyRootCanChange) {
        for (Path candidate : candidates) {
            Path real;
            try {
                real = candidate.toRealPath();
            } catch (IOException e) {
                continue; // missing or unreadable: try the next location
            }
            if (Files.isRegularFile(real) && Files.isExecutable(real) && everyLevel(real, onlyRootCanChange)) {
                return Optional.of(real);
            }
        }
        return Optional.empty();
    }

    private static boolean everyLevel(Path file, Predicate<Path> check) {
        for (Path level = file; level != null; level = level.getParent()) {
            if (!check.test(level)) {
                return false;
            }
        }
        return true;
    }

    /** Owned by uid 0 and neither group- nor world-writable; anything unreadable counts as not. */
    static boolean onlyRootCanChange(Path path) {
        try {
            Object uid = Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
            Set<PosixFilePermission> mode = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            return Integer.valueOf(0).equals(uid)
                    && !mode.contains(PosixFilePermission.GROUP_WRITE) && !mode.contains(PosixFilePermission.OTHERS_WRITE);
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return false;
        }
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
