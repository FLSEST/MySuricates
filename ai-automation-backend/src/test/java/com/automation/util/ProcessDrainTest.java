package com.automation.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessDrainTest {

    private static Process start(String script) throws IOException {
        return new ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start();
    }

    @Test
    void returnsTheSameTrimmedOutputAsReadAllBytes_withAndWithoutListener() throws Exception {
        String script = "printf 'a\\r\\nb\\n\\n  '";
        Process reference = start(script);
        String expected = new String(reference.getInputStream().readAllBytes()).trim();
        reference.waitFor(5, TimeUnit.SECONDS);

        List<String> lines = new CopyOnWriteArrayList<>();
        String withListener = ProcessDrain.drain(start(script), 5, "test", (line, dropped) -> lines.add(line));
        String withoutListener = ProcessDrain.drain(start(script), 5, "test", LineListener.NONE);

        assertThat(withListener).isEqualTo(expected).isEqualTo(withoutListener);
        assertThat(lines).containsExactly("a", "b", "", "  ");
    }

    @Test
    void timeoutKeepsTheFormerMessage() {
        assertThatThrownBy(() -> ProcessDrain.drain(start("sleep 30"), 1, "Host Claude action", LineListener.NONE))
                .isInstanceOf(IOException.class)
                .hasMessage("Host Claude action timed out after 1 seconds");
    }

    @Test
    void linesArriveWhileTheProcessIsStillRunning() throws Exception {
        CountDownLatch firstLine = new CountDownLatch(1);
        AtomicBoolean processAliveAtFirstLine = new AtomicBoolean();
        Process proc = start("echo one; sleep 3; echo two");

        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                ProcessDrain.drain(proc, 10, "test", (line, dropped) -> {
                    if ("one".equals(line)) {
                        processAliveAtFirstLine.set(proc.isAlive());
                        firstLine.countDown();
                    }
                });
            } catch (IOException ignored) {
                // asserted via the latch
            }
        });

        assertThat(firstLine.await(2, TimeUnit.SECONDS)).as("first line streamed before exit").isTrue();
        assertThat(processAliveAtFirstLine).isTrue();
        waiter.join(10_000);
    }

    @Test
    void readsThePipeOnAVirtualThread() throws Exception {
        AtomicBoolean virtual = new AtomicBoolean();
        ProcessDrain.drain(start("echo x"), 5, "test", (line, dropped) -> virtual.set(Thread.currentThread().isVirtual()));

        assertThat(virtual).isTrue();
    }

    @Test
    void readAllTeesIntoTheListenerAndReturnsAllBytes() throws IOException {
        byte[] input = "eins\nzwei".getBytes(StandardCharsets.UTF_8);
        List<String> lines = new CopyOnWriteArrayList<>();

        byte[] result = ProcessDrain.readAll(new ByteArrayInputStream(input), (line, dropped) -> lines.add(line));

        assertThat(result).isEqualTo(input);
        assertThat(lines).containsExactly("eins", "zwei");
    }

    @Test
    void reportsTheProcessToTheBoundTrackerForItsWholeRuntime() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        ProcessTracking.Tracker tracker = new ProcessTracking.Tracker() {
            @Override public void started(Process process) { events.add("started:" + process.isAlive()); }
            @Override public void finished(Process process) { events.add("finished"); }
        };

        String out = ProcessTracking.bind(tracker, () -> {
            try {
                return ProcessDrain.drain(start("sleep 0.2; echo done"), 5, "test", LineListener.NONE);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(out).isEqualTo("done");
        assertThat(events).containsExactly("started:true", "finished");
        assertThat(ProcessTracking.current()).isSameAs(ProcessTracking.Tracker.NONE);
    }
}
