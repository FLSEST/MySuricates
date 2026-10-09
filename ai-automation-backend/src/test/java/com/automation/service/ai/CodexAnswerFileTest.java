package com.automation.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

// Review 2026-10-09: the answer file comes from a process that runs the prompt's content
class CodexAnswerFileTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a plain file is read and trimmed; a missing or empty one is no answer")
    void plainFile() throws IOException {
        Path answer = tmp.resolve("answer.txt");
        assertThat(CodexAnswerFile.read(answer)).isEmpty();
        Files.writeString(answer, "");
        assertThat(CodexAnswerFile.read(answer)).isEmpty();
        Files.writeString(answer, "  42\n");
        assertThat(CodexAnswerFile.read(answer)).contains("42");
    }

    @Test
    @DisplayName("a link in place of the answer is not followed, whatever it points at")
    void linkIgnored() throws IOException {
        Path secret = Files.writeString(tmp.resolve("not-for-codex"), "DUMMY");
        Path answer = Files.createSymbolicLink(tmp.resolve("answer.txt"), secret);

        assertThat(CodexAnswerFile.read(answer)).isEmpty();
    }

    @Test
    @DisplayName("a directory in place of the answer is no answer either")
    void directoryIgnored() throws IOException {
        assertThat(CodexAnswerFile.read(Files.createDirectories(tmp.resolve("answer.txt")))).isEmpty();
    }
}
