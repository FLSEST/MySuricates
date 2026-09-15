package com.automation.service.workflow;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeOutputSeederTest {

    private static StepResult step(int index, StepStatus status, String name, String output) {
        Map<String, Object> md = name == null ? Map.of("blockType", "shell") : Map.of("blockType", "shell", "blockName", name);
        return StepResult.builder().stepIndex(index).status(status).output(output).metadata(md).build();
    }

    @Test
    void seedsNamedOutputsAndSourceIndexBeforeResumePoint() {
        ExecutionContext ctx = ExecutionContext.builder().workflowRunId("run-1").build();
        List<StepResult> persisted = List.of(
                step(0, StepStatus.COMPLETED, "backend_tests", "LOG"),
                step(1, StepStatus.FAILED, "lint", "LINT"),
                step(2, StepStatus.COMPLETED, null, "unnamed"),
                step(3, StepStatus.RUNNING, "report", "partial"));

        ResumeOutputSeeder.seed(ctx, persisted, 3);

        assertThat(ctx.getBlockOutput("backend_tests")).isEqualTo("LOG");
        assertThat(ctx.getBlockOutput("lint")).isEqualTo("LINT");
        assertThat(ctx.getBlockOutput("report")).isNull();
        assertThat(ctx.getSourceSteps().find("lint").orElseThrow().status()).isEqualTo(StepStatus.FAILED);
        assertThat(ctx.getSourceSteps().last().orElseThrow().stepIndex()).isEqualTo(2);
        assertThat(ctx.resolveTemplate("{{ block.backend_tests.output }}")).isEqualTo("LOG");
    }

    @Test
    void doesNotTaintSeededOutputs_taintComesFromExportedNames() {
        ExecutionContext ctx = ExecutionContext.builder().workflowRunId("run-1").build();

        ResumeOutputSeeder.seed(ctx, List.of(step(0, StepStatus.COMPLETED, "a", "x")), 1);

        assertThat(ctx.hasUntrustedInput()).isFalse();
    }
}
