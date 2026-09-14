package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.ContainerShellService;
import com.automation.service.cli.ShellCommandService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkspaceCheckTest {
    @TempDir Path directory;

    @Test
    void pipelineFailureRemainsFailureAndRetainsEvidence() throws Exception {
        var context = ExecutionContext.builder().workflowRunId("run").build();
        context.setProjectPath(directory.toString());
        context.setRunMetadata("workspace", Map.of("logsPath", directory.toString(), "sourceCommit", "abc"));
        var executor = new ShellBlockExecutor(new ShellCommandService(), mock(ContainerShellService.class));
        var block = new BlockDefinition();
        block.setType("shell");
        block.setConfig(Map.of("command", "(echo compiler-failure; exit 7) | tail -10",
                "checked_command", true));
        var step = executor.execute(block, context);
        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getMetadata()).containsEntry("exitCode", 7).containsEntry("sourceCommit", "abc");
        assertThat(Files.readString(Path.of((String) step.getMetadata().get("logPath"))))
                .contains("compiler-failure");
    }

    @Test
    void cancelBeforeProcessRegistrationKillsTheProcess() throws Exception {
        var context = ExecutionContext.builder().workflowRunId("run").build();
        context.cancel();
        var process = new ProcessBuilder("sleep", "30").start();
        try {
            context.registerProcess(process);
            assertThat(process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(process.isAlive()).isFalse();
        } finally {
            process.destroyForcibly();
        }
    }
}
