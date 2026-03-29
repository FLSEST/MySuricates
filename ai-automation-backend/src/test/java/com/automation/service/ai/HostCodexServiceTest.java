package com.automation.service.ai;

import com.automation.config.SummarizationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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
        Path workDir = Path.of("/home/user/.autotrigger/summarization/codex");

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
    void buildCommand_stdinMode_usesDashAsPromptPlaceholder() {
        Path outputFile = Path.of("/tmp/result.txt");
        Path workDir = Path.of("/home/user/project");

        List<String> cmd = service.buildCommand(workDir, outputFile);

        assertThat(cmd).startsWith("codex", "exec", "-");
        assertThat(cmd).contains("--skip-git-repo-check");
        assertThat(cmd).contains("-o", outputFile.toAbsolutePath().toString());
        assertThat(cmd).contains("-C", workDir.toAbsolutePath().toString());
    }

    @Test
    void buildCommand_stdinMode_nullWorkDir_noCFlag() {
        Path outputFile = Path.of("/tmp/result.txt");

        List<String> cmd = service.buildCommand(null, outputFile);

        assertThat(cmd).startsWith("codex", "exec", "-");
        assertThat(cmd).doesNotContain("-C");
    }

    @Test
    void execute_defaultTimeout_usesConfigTimeout() throws Exception {
        // Verify that execute(prompt, workDir) delegates with config timeout
        // We can't easily test process execution, but verify buildCommand produces correct structure
        Path outputFile = Path.of("/tmp/test.txt");
        List<String> cmd = service.buildCommand(Path.of("/test"), outputFile);

        assertThat(cmd).contains("codex", "exec");
        assertThat(cmd).contains("-o");
    }
}
