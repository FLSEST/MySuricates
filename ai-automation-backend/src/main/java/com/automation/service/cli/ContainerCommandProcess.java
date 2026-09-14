package com.automation.service.cli;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Owns the named ephemeral container so cancellation removes the actual work, not only Docker CLI. */
final class ContainerCommandProcess {
    private ContainerCommandProcess() {}

    static ShellCommandService.ShellResult execute(List<String> command, int timeout,
                                                   BooleanSupplier cancelled) {
        long started = System.currentTimeMillis();
        String name = "mysuricates-" + UUID.randomUUID();
        var arguments = new ArrayList<>(command);
        arguments.addAll(2, List.of("--name", name));
        Process process = null;
        var output = new ByteArrayOutputStream();
        int exit = 1;
        try {
            if (cancelled.getAsBoolean()) return result("Cancelled before container start", 130, started);
            process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
            Process child = process;
            Thread reader = Thread.ofVirtual().start(() -> {
                try (var stream = child.getInputStream()) { stream.transferTo(output); }
                catch (java.io.IOException ignored) { /* process terminated */ }
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
        } catch (java.io.IOException e) {
            return result(output + "\nError: " + e.getMessage(), 1, started);
        } finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                try {
                    Process cleanup = new ProcessBuilder("docker", "rm", "-f", name)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                    if (!cleanup.waitFor(5, TimeUnit.SECONDS)) cleanup.destroyForcibly();
                } catch (Exception ignored) { /* Docker may already have removed the --rm container. */ }
            }
        }
    }

    private static ShellCommandService.ShellResult result(String output, int code, long started) {
        return new ShellCommandService.ShellResult(output.trim(), code, System.currentTimeMillis() - started);
    }
}
