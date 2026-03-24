package com.automation.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Shared utilities for host process execution:
 * <ul>
 *   <li>{@link #getHostUid()} — cached UID lookup with config override and fallback</li>
 *   <li>{@link #drain(Process, int, String)} — robust stdout drain with timeout</li>
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
        InputStream stdout = proc.getInputStream();
        CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(() -> {
            try { return stdout.readAllBytes(); }
            catch (IOException e) { return new byte[0]; }
        });

        try {
            boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                proc.waitFor(5, TimeUnit.SECONDS);
                outputFuture.cancel(true);
                closeQuietly(stdout);
                throw new IOException(label + " timed out after " + timeoutSeconds + " seconds");
            }

            return new String(outputFuture.get(5, TimeUnit.SECONDS)).trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            outputFuture.cancel(true);
            closeQuietly(stdout);
            throw new IOException(label + " interrupted");
        } catch (ExecutionException | TimeoutException e) {
            proc.destroyForcibly();
            outputFuture.cancel(true);
            closeQuietly(stdout);
            throw new IOException("Failed to read " + label + " output", e);
        }
    }

    private void closeQuietly(InputStream stream) {
        try { stream.close(); } catch (IOException ignored) { /* already destroyed */ }
    }
}
