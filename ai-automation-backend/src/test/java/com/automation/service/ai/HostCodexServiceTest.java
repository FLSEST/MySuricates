package com.automation.service.ai;

import com.automation.config.SummarizationConfig;
import com.automation.service.live.LiveOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HostCodexServiceTest {

    private HostCodexService service;

    @BeforeEach
    void setUp() {
        SummarizationConfig config = new SummarizationConfig();
        config.setTimeoutSeconds(120);
        service = new HostCodexService(config);
    }

    @Test
    void buildCommand_basic() {
        Path outputFile = Path.of("/tmp/result.txt");
        Path workDir = Path.of("/home/user/.mysuricates/summarization/codex");

        List<String> cmd = service.buildCommand("test prompt", workDir, outputFile);

        assertThat(cmd).startsWith("codex", "exec", "test prompt");
        assertThat(cmd).contains("--skip-git-repo-check");
        assertThat(cmd).contains("-o", outputFile.toAbsolutePath().toString());
        assertThat(cmd).contains("-C", workDir.toAbsolutePath().toString());
    }

    @Test
    void buildCommand_nullWorkDir() {
        Path outputFile = Path.of("/tmp/result.txt");

        List<String> cmd = service.buildCommand("prompt", null, outputFile);

        assertThat(cmd).doesNotContain("-C");
    }

    @Test
    void buildCommand_withModel_addsMFlag() {
        Path outputFile = Path.of("/tmp/result.txt");

        List<String> cmd = service.buildCommand(null, outputFile, true, " gpt-5.5 ");

        assertThat(cmd).containsSequence("-m", "gpt-5.5");
        assertThat(cmd).containsSequence("--sandbox", "read-only");
    }

    @Test
    void buildCommand_blankOrNullModel_noMFlag() {
        Path outputFile = Path.of("/tmp/result.txt");

        assertThat(service.buildCommand(null, outputFile, false, "   ")).doesNotContain("-m");
        assertThat(service.buildCommand(null, outputFile, false, null)).doesNotContain("-m");
        assertThat(service.buildCommand(null, outputFile, false)).doesNotContain("-m");
    }

    @Test
    void buildCommand_stdinMode_usesDashAsPromptPlaceholder() {
        Path outputFile = Path.of("/tmp/result.txt");
        Path workDir = Path.of("/home/user/project");

        List<String> cmd = service.buildCommand(workDir, outputFile, false);

        assertThat(cmd).startsWith("codex", "exec", "-");
        assertThat(cmd).contains("--skip-git-repo-check");
        assertThat(cmd).contains("-o", outputFile.toAbsolutePath().toString());
        assertThat(cmd).contains("-C", workDir.toAbsolutePath().toString());
        assertThat(cmd).doesNotContain("--sandbox");
    }

    @Test
    void buildCommand_stdinMode_nullWorkDir_noCFlag() {
        Path outputFile = Path.of("/tmp/result.txt");

        List<String> cmd = service.buildCommand(null, outputFile, false);

        assertThat(cmd).startsWith("codex", "exec", "-");
        assertThat(cmd).doesNotContain("-C");
    }

    @Test
    void buildCommand_readOnly_addsSandboxFlag() {
        Path outputFile = Path.of("/tmp/result.txt");
        Path workDir = Path.of("/home/user/project");

        List<String> cmd = service.buildCommand(workDir, outputFile, true);

        assertThat(cmd).contains("--sandbox", "read-only");
        assertThat(cmd).contains("-C", workDir.toAbsolutePath().toString());
    }

    @Test
    void buildCommand_notReadOnly_noSandboxFlag() {
        Path outputFile = Path.of("/tmp/result.txt");

        List<String> cmd = service.buildCommand(null, outputFile, false);

        assertThat(cmd).doesNotContain("--sandbox");
        assertThat(cmd).doesNotContain("read-only");
    }

    @Test
    void execute_defaultTimeout_usesConfigTimeout() {
        Path outputFile = Path.of("/tmp/test.txt");
        List<String> cmd = service.buildCommand(Path.of("/test"), outputFile, false);

        assertThat(cmd).contains("codex", "exec");
        assertThat(cmd).contains("-o");
    }

    // --- live output ---

    @Test
    void execute_streamsProgressLinesIntoTheBoundLiveSink_resultStillFromOutputFile() {
        SummarizationConfig config = new SummarizationConfig();
        config.setTimeoutSeconds(30);
        HostCodexService svc = new HostCodexService(config) {
            @Override
            List<String> buildCommand(Path workingDirectory, Path outputFile, boolean readOnly, String model) {
                return List.of("/bin/sh", "-c",
                        "cat >/dev/null; echo 'thinking…'; echo 'running tests'; printf 'final answer' > \"$1\"",
                        "sh", outputFile.toString());
            }
        };
        List<String> lines = new ArrayList<>();

        String result = LiveOutput.bind((kind, text) -> lines.add(kind + ":" + text), () -> {
            try {
                return svc.execute("prompt", null, 30, true, null);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        assertThat(result).isEqualTo("final answer");
        assertThat(lines).containsExactly("LINE:thinking…", "LINE:running tests");
    }

    // --- Broken sandbox (bwrap) must fail the block instead of passing a blind answer ---

    @Test
    void sandboxFailure_detectsBwrapErrorsFromToolExecution() {
        String output = String.join("\n",
                "exec bash -lc 'ls src' in /work",
                "bwrap: setting up uid map: Permission denied",
                "codex",
                "I could not read the repository, so here is a general answer.");

        assertThat(HostCodexService.sandboxFailure(output, "I could not read the repository, so here is a general answer."))
                .contains("bwrap: setting up uid map: Permission denied");
    }

    @Test
    void sandboxFailure_ignoresOutputWithoutSandboxErrors() {
        String output = "exec bash -lc 'ls src' in /work\nMain.java\ncodex\nAll good.";

        assertThat(HostCodexService.sandboxFailure(output, "All good.")).isEmpty();
        assertThat(HostCodexService.sandboxFailure(null, "All good.")).isEmpty();
    }

    @Test
    void sandboxFailure_ignoresTheModelQuotingTheErrorInItsAnswer() {
        String answer = "Explanation of the log line:\nbwrap: setting up uid map: Permission denied\nmeans user namespaces are off.";
        String output = "codex\n" + answer;

        assertThat(HostCodexService.sandboxFailure(output, answer)).isEmpty();
    }

    @Test
    void sandboxFailure_toleratesIndentedToolOutput() {
        assertThat(HostCodexService.sandboxFailure("  bwrap: Creating new namespace failed: Operation not permitted\n", "done"))
                .contains("bwrap: Creating new namespace failed: Operation not permitted");
    }
}
