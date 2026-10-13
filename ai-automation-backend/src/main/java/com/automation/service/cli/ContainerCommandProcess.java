package com.automation.service.cli;

import com.automation.service.live.LiveOutput;
import com.automation.util.LineListener;
import com.automation.util.ProcessOutputTee;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Owns the named ephemeral container so cancellation removes the actual work, not only Docker CLI. */
final class ContainerCommandProcess {
    /** How long a run waits for {@code docker rm} after its container process ended or was cancelled. */
    private static final int REMOVE_WAIT_SECONDS = 5;

    private ContainerCommandProcess() {}

    static ShellCommandService.ShellResult execute(List<String> command, int timeout,
                                                   BooleanSupplier cancelled) {
        return execute(command, timeout, cancelled, name -> List.of("docker", "rm", "-f", name));
    }

    /** Test seam: {@code removeCommand} builds the command that removes the named container. */
    static ShellCommandService.ShellResult execute(List<String> command, int timeout, BooleanSupplier cancelled,
                                                   Function<String, List<String>> removeCommand) {
        long started = System.currentTimeMillis();
        String name = "mysuricates-" + UUID.randomUUID();
        var arguments = new ArrayList<>(command);
        arguments.addAll(2, List.of("--name", name));
        Process process = null;
        var output = new ByteArrayOutputStream();
        LineListener live = LiveOutput.current().rawLines();
        int exit = 1;
        try {
            if (cancelled.getAsBoolean()) return result("Cancelled before container start", 130, started);
            process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
            Process child = process;
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var stream = child.getInputStream(); var tee = ProcessOutputTee.wrap(output, live)) {
                    stream.transferTo(tee);
                }
                catch (IOException ignored) { /* process terminated */ }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
            while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) { exit = 130; break; }
                if (System.nanoTime() >= deadline) { exit = 124; break; }
            }
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            } else {
                exit = process.exitValue();
            }
            reader.join(2000);
            return result(output.toString() + (exit == 124 ? "\nCommand timed out" : ""), exit, started);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result(output + "\nCommand interrupted", 130, started);
        } catch (IOException e) {
            return result(output + "\nError: " + e.getMessage(), 1, started);
        } finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                removeContainer(removeCommand.apply(name));
            }
        }
    }

    /**
     * Removes the container and waits a bounded time for it.
     *
     * <p>A cancelled run arrives here with the interrupt flag set (the catch above restores it), and a
     * wait on an interrupted thread throws at once. The container would then still run — and keep writing
     * into the mounted working directory — after the run already counts as cancelled. So the flag is
     * cleared for this one wait of at most {@value #REMOVE_WAIT_SECONDS} seconds and set again afterwards:
     * the caller still sees the interruption, only a little later.
     */
    private static void removeContainer(List<String> removeCommand) {
        boolean interrupted = Thread.interrupted();
        try {
            Process cleanup = new ProcessBuilder(removeCommand)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!cleanup.waitFor(REMOVE_WAIT_SECONDS, TimeUnit.SECONDS)) cleanup.destroyForcibly();
        } catch (InterruptedException e) {
            // Interrupted again while waiting: stop waiting, the removal goes on by itself.
            interrupted = true;
        } catch (IOException ignored) {
            // The Docker CLI cannot be started, so there is nothing to remove the container with.
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static ShellCommandService.ShellResult result(String output, int code, long started) {
        return new ShellCommandService.ShellResult(output.trim(), code, System.currentTimeMillis() - started);
    }
}
