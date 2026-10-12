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
        service = new HostCodexService(config, AiCliThrottles.defaults());
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
        HostCodexService svc = new HostCodexService(config, AiCliThrottles.defaults()) {
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
                return svc.execute("prompt", null, 30, CodexSandbox.READ_ONLY, null, false);
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

        assertThat(HostCodexService.sandboxFailure(output, "I could not read the repository, so here is a general answer.", null))
                .contains("bwrap: setting up uid map: Permission denied");
    }

    @Test
    void sandboxFailure_ignoresOutputWithoutSandboxErrors() {
        String output = "exec bash -lc 'ls src' in /work\nMain.java\ncodex\nAll good.";

        assertThat(HostCodexService.sandboxFailure(output, "All good.", null)).isEmpty();
        assertThat(HostCodexService.sandboxFailure(null, "All good.", null)).isEmpty();
    }

    @Test
    void sandboxFailure_ignoresTheModelQuotingTheErrorInItsAnswer() {
        String answer = "Explanation of the log line:\nbwrap: setting up uid map: Permission denied\nmeans user namespaces are off.";
        String output = "codex\n" + answer;

        assertThat(HostCodexService.sandboxFailure(output, answer, null)).isEmpty();
    }

    @Test
    void sandboxFailure_toleratesIndentedToolOutput() {
        assertThat(HostCodexService.sandboxFailure("  bwrap: Creating new namespace failed: Operation not permitted\n", "done", null))
                .contains("bwrap: Creating new namespace failed: Operation not permitted");
    }

    // --- 51958d17: loopback failure, error-only answers, prompt quotes ---

    @Test
    void sandboxFailure_detectsTheLoopbackFailureOfAParallelRun() {
        String output = String.join("\n",
                "exec bash -lc 'cat README.md' in /work",
                " exited 1 in 4ms:",
                "bwrap: loopback: Failed RTM_NEWADDR: Operation not permitted",
                "codex",
                "Ich konnte die Dateien nicht lesen.");

        assertThat(HostCodexService.sandboxFailure(output, "Ich konnte die Dateien nicht lesen.", "Review the README"))
                .contains("bwrap: loopback: Failed RTM_NEWADDR: Operation not permitted");
    }

    @Test
    void sandboxFailure_detectsTheMarkerAfterAColon() {
        String output = "ERROR codex_core::exec: sandbox failed: bwrap: loopback: Failed RTM_NEWADDR: Operation not permitted";

        assertThat(HostCodexService.sandboxFailure(output, "done", null)).isPresent();
    }

    @Test
    void sandboxFailure_anAnswerThatIsOnlyTheErrorIsNoAnswer() {
        String error = "bwrap: loopback: Failed RTM_NEWADDR: Operation not permitted";

        assertThat(HostCodexService.sandboxFailure("codex\n" + error + "\n" + error, error, "Review the diff"))
                .contains(error);
        assertThat(HostCodexService.sandboxFailure(null, "\n  " + error + "  \n", null)).contains(error);
    }

    @Test
    void sandboxFailure_ignoresTheErrorQuotedInThePrompt() {
        String prompt = "TODO: Codex-Review scheitert an der Sandbox:\nbwrap: loopback: Failed RTM_NEWADDR: Operation not permitted";
        String answer = "The fix belongs in HostCodexService.";

        assertThat(HostCodexService.sandboxFailure("user\n" + prompt + "\ncodex\n" + answer, answer, prompt)).isEmpty();
    }

    @Test
    void sandboxFailure_ignoresTheMarkerQuotedInSourceCode() {
        String output = String.join("\n",
                "exec bash -lc 'cat HostCodexService.java' in /work",
                "    private static final List<String> PREFIXES = List.of(\"bwrap:\");",
                "     * without user namespaces (\"bwrap: setting up uid map: Permission denied\").",
                "codex",
                "Looks fine.");

        assertThat(HostCodexService.sandboxFailure(output, "Looks fine.", null)).isEmpty();
    }

    /**
     * Review w8: a Codex review that reads this class and its test must not fail — the marker after a
     * colon inside source or a test string is not Codex' own error line.
     */
    @Test
    void sandboxFailure_ignoresSourceAndTestLinesAboutBwrap_inToolOutputAndAnswer() {
        String javadoc = "     * start the line or follow a blank or colon (\"exec failed: bwrap: …\"); quoted in source code";
        String testLine = "        String output = \"ERROR codex_core::exec: sandbox failed: bwrap: loopback: Failed RTM_NEWADDR: Operation not permitted\";";
        String answer = "HostCodexService.java:40 documents the marker:\n" + javadoc.trim()
                + "\nHostCodexServiceTest.java:203 checks it:\n" + testLine.trim();
        String output = String.join("\n",
                "exec bash -lc 'sed -n 36,45p src/main/java/com/automation/service/ai/HostCodexService.java' in /work",
                javadoc,
                "exec bash -lc 'sed -n 200,206p src/test/java/com/automation/service/ai/HostCodexServiceTest.java' in /work",
                testLine,
                "codex",
                "Review done, see answer.");

        assertThat(HostCodexService.sandboxFailure(output, "Review done, see answer.", null)).isEmpty();
        assertThat(HostCodexService.sandboxFailure(output + "\n" + answer, answer, null)).isEmpty();
        assertThat(HostCodexService.sandboxFailure(null, javadoc + "\n" + testLine, null)).isEmpty();
    }
}
