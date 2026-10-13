package com.automation.service.cli;

import com.automation.service.live.LiveOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerCommandProcessTest {

    /**
     * ContainerCommandProcess inserts {@code --name <container>} at argument index 2 (after
     * {@code docker run}). A shell script as the first two arguments ignores those extra arguments,
     * so the process runs without Docker.
     */
    @Test
    void execute_streamsOutputLinesIntoTheBoundLiveSink(@TempDir Path dir) throws IOException {
        Path script = Files.writeString(dir.resolve("fake-docker.sh"), "echo gestartet\necho fertig\n");
        List<String> lines = new CopyOnWriteArrayList<>();

        ShellCommandService.ShellResult result = LiveOutput.bind((kind, text) -> lines.add(kind + ":" + text),
                () -> ContainerCommandProcess.execute(List.of("/bin/sh", script.toString()), 10, () -> false));

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("gestartet\nfertig");
        assertThat(lines).containsExactly("LINE:gestartet", "LINE:fertig");
    }

    /**
     * Cancelling a run interrupts its thread. The interruption must survive the container cleanup in
     * the finally block (else the caller never learns about it), and the cleanup must still wait for
     * the container removal instead of giving up at once on the interrupted thread.
     */
    @Test
    void execute_interrupted_keepsTheInterruptFlagAndWaitsForTheContainerRemoval(@TempDir Path dir)
            throws Exception {
        Path startedMarker = dir.resolve("started");
        Path removedMarker = dir.resolve("removed");
        Path script = Files.writeString(dir.resolve("fake-docker.sh"),
                "touch '" + startedMarker + "'\nexec sleep 30\n");
        List<String> remove = List.of("/bin/sh", "-c", "sleep 0.5 && touch \"$1\"", "fake-docker-rm",
                removedMarker.toString());
        AtomicReference<ShellCommandService.ShellResult> result = new AtomicReference<>();
        AtomicBoolean interruptedAfterReturn = new AtomicBoolean();
        AtomicBoolean removedBeforeReturn = new AtomicBoolean();

        Thread worker = Thread.ofPlatform().start(() -> {
            result.set(ContainerCommandProcess.execute(List.of("/bin/sh", script.toString()), 60, () -> false,
                    name -> remove));
            removedBeforeReturn.set(Files.exists(removedMarker));
            interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.exists(startedMarker) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(startedMarker).as("fake container started").exists();
        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(20));

        assertThat(worker.isAlive()).isFalse();
        assertThat(result.get().exitCode()).isEqualTo(130);
        assertThat(result.get().output()).contains("Command interrupted");
        assertThat(interruptedAfterReturn).as("interrupt flag still set for the caller").isTrue();
        assertThat(removedBeforeReturn).as("container removal awaited before returning").isTrue();
    }
}
