package com.automation.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Drains a process's stdout while waiting for it with a timeout — the one shared copy of what
 * HostClaudeService, HostPiService and HostProcessUtils used to duplicate. The pipe is read on a
 * virtual thread (a blocking read no longer parks a common-pool thread) and optionally teed into
 * a {@link LineListener}, so callers can stream lines while the process runs.
 */
public final class ProcessDrain {

    /** Reader threads for process pipes: blocking I/O belongs on virtual threads. */
    public static final Executor READERS = task -> Thread.ofVirtual().name("process-drain").start(task);

    private ProcessDrain() {}

    /**
     * Waits for {@code proc}, returns its trimmed stdout and ensures the stream is closed — even on
     * timeout or interruption. Capture {@code live} on the calling thread; it is invoked from the
     * reader thread.
     *
     * @throws IOException on timeout ("{label} timed out after N seconds"), interruption or read failure
     */
    public static String drain(Process proc, int timeoutSeconds, String label, LineListener live) throws IOException {
        // Inside a workflow step the run can now kill this process on cancel (see ProcessTracking)
        ProcessTracking.Tracker tracker = ProcessTracking.current();
        tracker.started(proc);
        try {
            return drainTracked(proc, timeoutSeconds, label, live);
        } finally {
            tracker.finished(proc);
        }
    }

    private static String drainTracked(Process proc, int timeoutSeconds, String label, LineListener live) throws IOException {
        InputStream stdout = proc.getInputStream();
        CompletableFuture<byte[]> outputFuture = CompletableFuture.supplyAsync(() -> {
            try { return readAll(stdout, live); }
            catch (IOException e) { return new byte[0]; }
        }, READERS);

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

    /** Reads {@code in} to EOF, forwarding lines to {@code live} on the way. */
    public static byte[] readAll(InputStream in, LineListener live) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (OutputStream tee = ProcessOutputTee.wrap(buffer, live)) {
            in.transferTo(tee);
        }
        return buffer.toByteArray();
    }

    private static void closeQuietly(InputStream stream) {
        try { stream.close(); } catch (IOException ignored) { /* already destroyed */ }
    }
}
