package com.automation.service.cli;

import com.automation.service.live.LiveOutput;
import com.automation.service.live.LiveOutputSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void execute_envAssignmentPrefix_isVisibleInsideQuotedHeredoc() {
        ShellCommandService.ShellResult result = service.execute(
                "PROJECT_ID='abc' python3 - <<'PY'\nimport os\nprint(os.environ['PROJECT_ID'])\nPY", null, 10);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output().trim()).isEqualTo("abc");
    }

    // --- live output ---

    @Test
    void execute_streamsLinesIntoTheBoundLiveSink_whileTheCommandRuns() throws InterruptedException {
        List<String> lines = new CopyOnWriteArrayList<>();
        CountDownLatch firstLine = new CountDownLatch(1);
        LiveOutputSink sink = (kind, text) -> {
            lines.add(kind + ":" + text);
            firstLine.countDown();
        };
        AtomicReference<ShellCommandService.ShellResult> result = new AtomicReference<>();

        Thread run = Thread.ofVirtual().start(() -> result.set(LiveOutput.bind(sink,
                () -> service.execute("echo eins; sleep 2; echo zwei >&2", null, 30))));

        assertThat(firstLine.await(1500, TimeUnit.MILLISECONDS)).as("line before the command finished").isTrue();
        run.join(10_000);
        assertThat(lines).containsExactly("LINE:eins", "LINE:zwei");
        assertThat(result.get().output()).isEqualTo("eins\nzwei");
    }

    @Test
    void execute_outputIsIdenticalWithAndWithoutLiveSink() {
        String command = "printf 'a\\r\\nb\\n\\n  c  \\n'";
        ShellCommandService.ShellResult plain = service.execute(command, null, 30);
        ShellCommandService.ShellResult streamed = LiveOutput.bind((kind, text) -> {},
                () -> service.execute(command, null, 30));

        assertThat(streamed.output()).isEqualTo(plain.output());
        assertThat(streamed.exitCode()).isEqualTo(plain.exitCode());
    }

    @Test
    void execute_timeout_stillDeliversThePartialLine() {
        List<String> lines = new CopyOnWriteArrayList<>();

        ShellCommandService.ShellResult result = LiveOutput.bind((kind, text) -> lines.add(text),
                () -> service.execute("printf 'halbe Zeile'; sleep 60", null, 1));

        assertThat(result.exitCode()).isEqualTo(124);
        assertThat(result.output()).contains("halbe Zeile").contains("timed out");
        assertThat(lines).containsExactly("halbe Zeile");
    }
}
