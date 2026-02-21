package com.automation.service.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ShellCommandServiceTest {

    private final ShellCommandService service = new ShellCommandService();

    @Test
    void execute_simpleCommand_returnsOutput() {
        ShellCommandService.ShellResult result = service.execute("echo hello", null, 30);

        assertThat(result.output()).isEqualTo("hello");
        assertThat(result.exitCode()).isZero();
        assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void execute_failingCommand_returnsNonZeroExitCode() {
        ShellCommandService.ShellResult result = service.execute("exit 42", null, 30);

        assertThat(result.exitCode()).isEqualTo(42);
    }

    @Test
    void execute_timeout_returnsTimeoutError() {
        ShellCommandService.ShellResult result = service.execute("sleep 60", null, 1);

        assertThat(result.exitCode()).isEqualTo(124);
        assertThat(result.output()).contains("timed out");
    }

    @Test
    void execute_workingDirectory_usesCorrectDir(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("testfile.txt"), "content");

        ShellCommandService.ShellResult result = service.execute(
                "ls testfile.txt", tempDir.toString(), 30);

        assertThat(result.output()).contains("testfile.txt");
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void execute_multilineOutput_capturedFully() {
        ShellCommandService.ShellResult result = service.execute(
                "echo line1 && echo line2 && echo line3", null, 30);

        assertThat(result.output()).contains("line1", "line2", "line3");
        assertThat(result.exitCode()).isZero();
    }

    @Test
    void execute_stderrCaptured() {
        ShellCommandService.ShellResult result = service.execute(
                "echo error_msg >&2", null, 30);

        assertThat(result.output()).contains("error_msg");
    }
}
