package com.automation.executor.blocks;

import com.automation.entity.StepResult;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.ShellCommandService.ShellResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceCheckEvidenceTest {

    @TempDir Path logs;

    private StepResult step(int index) {
        StepResult step = new StepResult();
        step.setStepIndex(index);
        step.setMetadata(Map.of("blockType", "shell"));
        return step;
    }

    @Test
    void withoutWorkspaceMetadata_nothingIsRecorded() {
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run").build();
        StepResult step = step(1);

        WorkspaceCheckEvidence.record(context, step, new ShellResult("out", 0, 5));

        assertThat(step.getMetadata()).containsOnlyKeys("blockType");
    }

    @Test
    void appendsOutputToStepLog_andRecordsEvidenceMetadata() throws Exception {
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run").build();
        context.setProjectPath("/work/run");
        context.setRunMetadata("workspace", Map.of("logsPath", logs.toString(), "sourceCommit", "abc123"));
        StepResult step = step(4);

        WorkspaceCheckEvidence.record(context, step, new ShellResult("first", 0, 1));
        WorkspaceCheckEvidence.record(context, step, new ShellResult("second", 1, 1));

        Path log = logs.resolve("step-4.log");
        assertThat(Files.readString(log)).isEqualTo("first\nsecond\n");
        assertThat(step.getMetadata())
                .containsEntry("blockType", "shell")
                .containsEntry("logPath", log.toString())
                .containsEntry("sourceCommit", "abc123")
                .containsEntry("workingDirectory", "/work/run");
    }

    @Test
    void unwritableLogDirectory_failsTheStep() {
        ExecutionContext context = ExecutionContext.builder().workflowRunId("run").build();
        context.setRunMetadata("workspace", Map.of("logsPath", logs.resolve("missing").toString()));

        assertThatThrownBy(() -> WorkspaceCheckEvidence.record(context, step(0), new ShellResult("x", 0, 1)))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("workspace check evidence");
    }
}
