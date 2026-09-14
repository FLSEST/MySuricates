package com.automation.service.cli;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ShellCommandService {

    public record ShellResult(String output, int exitCode, long durationMs) {}

    /**
     * Executes a shell command directly on the host via ProcessBuilder.
     * No Claude CLI involved — zero token cost, fast execution.
     */
    public ShellResult execute(String command, String workingDirectory, int timeoutSeconds) {
        return execute(command, workingDirectory, timeoutSeconds, null);
    }

    /**
     * Executes a shell command on the host. If a processCallback is provided,
     * it is called with the started Process so callers can track/cancel it.
     */
    public ShellResult execute(String command, String workingDirectory, int timeoutSeconds,
                               java.util.function.Consumer<Process> processCallback) {
        long start = System.currentTimeMillis();

        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
        pb.redirectErrorStream(true);
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            pb.directory(new File(workingDirectory));
        }

        log.debug("Shell execute (workDir: {}, timeout: {}s): {}", workingDirectory, timeoutSeconds,
                command.length() > 200 ? command.substring(0, 200) + "..." : command);

        Process proc = null;
        try {
            proc = pb.start();

            if (processCallback != null) {
                processCallback.accept(proc);
            }

            // Read output in background thread to prevent pipe deadlock
            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
            Process running = proc;
            Thread reader = Thread.ofVirtual().start(() -> {
                try (InputStream is = running.getInputStream()) {
                    is.transferTo(outputBuffer);
                } catch (IOException ignored) {
                    // Process destroyed — expected on timeout or cancel
                }
            });

            boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
                proc.waitFor(2, TimeUnit.SECONDS);
                reader.join(2000);
                long durationMs = System.currentTimeMillis() - start;
                log.warn("Shell command timed out after {}s: {}", timeoutSeconds, command);
                return new ShellResult(outputBuffer.toString() + "\nCommand timed out after " + timeoutSeconds + " seconds", 124, durationMs);
            }

            reader.join(5000);
            String output = outputBuffer.toString().trim();
            int exitCode = proc.exitValue();
            long durationMs = System.currentTimeMillis() - start;

            if (exitCode != 0) {
                log.debug("Shell command exited with code {}: {}", exitCode, command);
            }

            return new ShellResult(output, exitCode, durationMs);
        } catch (IOException e) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("Shell command failed: {}", e.getMessage());
            return new ShellResult("Error: " + e.getMessage(), 1, durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            long durationMs = System.currentTimeMillis() - start;
            return new ShellResult("Command interrupted", 130, durationMs);
        } finally {
            if (proc != null && proc.isAlive()) {
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
            }
        }
    }
}
