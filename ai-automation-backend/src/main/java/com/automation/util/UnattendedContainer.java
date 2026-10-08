package com.automation.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs the AI CLIs and shell blocks nobody watches in a fresh container instead of on the host (TODO 6ee57b87,
 * the separation TODO 9525820a waits for). {@link ProcessSandbox} hides the instance's secrets from such a
 * process but leaves it the host user's other services; in a container of the image
 * {@code automation-agent-workflow} it only has what {@link UnattendedContainerCommand} mounts: the working
 * directory, the CLI program and its login.
 *
 * <p>Startup switch {@code automation.hardening.unattended-in-container}. The same marker as the sandbox
 * decides ({@link ProcessSandbox#unattendedHere()}): processes of an attended run start on the host, all
 * others in a container — unattended runs and AI calls outside a run alike. Without Docker or without the
 * image the start fails instead of falling back to the host. Where both switches are on, the container
 * replaces the sandbox for these processes.
 *
 * <p>Killing the {@code docker run} client (timeout, cancel) leaves its container running, so
 * {@link #start(ProcessBuilder)} removes the container once the client has ended.
 */
public final class UnattendedContainer {

    static final String NAME_PREFIX = "mysuricates-unattended-";

    /**
     * What the configuration decides at startup.
     *
     * @param setup   everything per start besides the command — computed per start, files may appear later
     * @param remover removes a container by name ({@code docker rm -f}), called after its client ended
     */
    public record Policy(boolean enabled, Supplier<UnattendedContainerCommand.Setup> setup, Consumer<String> remover) {
        public Policy {
            Objects.requireNonNull(setup, "setup");
            Objects.requireNonNull(remover, "remover");
        }
    }

    private static final Policy OFF = new Policy(false, () -> {
        throw new IllegalStateException("Unattended container mode is off");
    }, name -> { });
    private static volatile Policy policy = OFF;

    private UnattendedContainer() {
    }

    /** Set once at startup by {@code UnattendedContainerConfiguration}; tests set and {@link #reset} it. */
    public static void configure(Policy newPolicy) {
        policy = Objects.requireNonNull(newPolicy, "policy");
    }

    static void reset() {
        policy = OFF;
    }

    /** Whether a process started from this thread now runs in a container. */
    public static boolean active() {
        return policy.enabled() && ProcessSandbox.unattendedHere();
    }

    /**
     * Turns the builder into the {@code docker run} of its command when {@link #active()}: working directory
     * (set it first) and {@code writable} directories under their own paths, the login variables of an AI CLI
     * in the client's environment. Returns whether it did.
     */
    static boolean apply(ProcessBuilder pb, List<Path> writable) {
        if (!active()) {
            return false;
        }
        Path workdir = pb.directory() == null ? null : pb.directory().toPath();
        UnattendedContainerCommand.Plan plan = UnattendedContainerCommand.build(policy.setup().get(), pb.command(),
                workdir, writable, NAME_PREFIX + UUID.randomUUID());
        pb.environment().putAll(plan.clientEnvironment());
        pb.command(plan.command());
        return true;
    }

    /** Starts the process; for a container, removes it once the client has ended, however that happened. */
    public static Process start(ProcessBuilder pb) throws IOException {
        Process process = pb.start();
        Consumer<String> remover = policy.remover();
        containerName(pb.command()).ifPresent(name ->
                process.onExit().thenRunAsync(() -> remover.accept(name), ProcessDrain.READERS));
        return process;
    }

    /** The name of the container a command built here starts, if it is one. */
    static Optional<String> containerName(List<String> command) {
        if (command.size() < 4 || !"docker".equals(Path.of(command.get(0)).getFileName().toString())
                || !"run".equals(command.get(1))) {
            return Optional.empty();
        }
        int index = command.indexOf("--name");
        if (index < 0 || index + 1 >= command.size() || !command.get(index + 1).startsWith(NAME_PREFIX)) {
            return Optional.empty();
        }
        return Optional.of(command.get(index + 1));
    }
}
