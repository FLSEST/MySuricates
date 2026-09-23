package com.automation.service.staging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StagingComposeCommandTest {

    @TempDir
    Path tmp;

    private Path backendDir;
    private Path composeFile;
    private Path override;
    private final List<List<String>> calls = new ArrayList<>();
    private final List<Path> workDirs = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        backendDir = Files.createDirectories(tmp.resolve("repo/ai-automation-backend"));
        Files.createDirectories(tmp.resolve("repo/scripts"));
        Files.writeString(tmp.resolve("repo/scripts/staging.sh"), "#!/bin/sh\n");
        composeFile = Files.writeString(backendDir.resolve("docker-compose.stable.yml"), "services: {}\n");
        override = Files.writeString(tmp.resolve("stable.override.yml"), "services: {}\n");
    }

    private StagingComposeCommand withOutput(String output) {
        return new StagingComposeCommand((dir, cmd) -> {
            workDirs.add(dir);
            calls.add(cmd);
            return output;
        });
    }

    @Test
    void addsTheOverrideWrittenByStagingSh() {
        String[] cmd = withOutput(override + "\n").command(backendDir, composeFile, "stable", "up", "-d");

        assertThat(cmd).containsExactly("docker", "compose", "-f", composeFile.toString(),
                "-f", override.toString(), "up", "-d");
        assertThat(calls).containsExactly(List.of("bash",
                tmp.resolve("repo/scripts/staging.sh").toString(), "write-override", "stable"));
        assertThat(workDirs).containsExactly(backendDir);
    }

    @Test
    void warningsBeforeThePathAreNotMistakenForIt() {
        String output = "KI-Anbieter, die test nicht nutzen kann:\n  claude: keine Anmeldung\n" + override + "\n";

        String[] cmd = withOutput(output).command(backendDir, composeFile, "test", "down", "-v");

        assertThat(cmd).containsSubsequence("-f", override.toString(), "down", "-v");
        assertThat(calls.get(0)).endsWith("write-override", "test");
    }

    @Test
    void missingScriptIsAnError() throws IOException {
        Files.delete(tmp.resolve("repo/scripts/staging.sh"));

        assertThatThrownBy(() -> withOutput(override.toString()).command(backendDir, composeFile, "stable", "up", "-d"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scripts/staging.sh not found");
        assertThat(calls).isEmpty();
    }

    @Test
    void failedWriteIsAnError() {
        StagingComposeCommand compose = new StagingComposeCommand((dir, cmd) -> {
            throw new IOException("exit 2: Ungueltige STABLE_AI_*-Werte");
        });

        assertThatThrownBy(() -> compose.command(backendDir, composeFile, "stable", "up", "-d"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ungueltige STABLE_AI_*-Werte");
    }

    @Test
    void noOverrideFileNamedIsAnError() {
        assertThatThrownBy(() -> withOutput("").command(backendDir, composeFile, "stable", "up", "-d"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("named no override file");
        assertThatThrownBy(() -> withOutput(tmp.resolve("missing.yml") + "\n").command(backendDir, composeFile, "stable", "down"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("named no override file");
    }

    /** A compose call that bypasses this class would start Stable/Test without the host mounts policy. */
    @Test
    void stagingServicesCallComposeOnlyThroughThisClass() throws IOException {
        Path dir = Path.of("src/main/java/com/automation/service/staging");
        for (String name : List.of("StagingService.java", "TestingService.java")) {
            String source = Files.readString(dir.resolve(name));
            assertThat(source).as(name).doesNotContain("\"compose\"");
            assertThat(source).as(name).contains("compose.command(");
        }
    }
}
