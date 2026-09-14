package com.automation.service.cli;

import com.automation.service.live.LiveOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
}
