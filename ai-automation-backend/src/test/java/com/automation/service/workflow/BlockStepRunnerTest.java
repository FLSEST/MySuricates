package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.BlockDefinition;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.repository.ContainerRepository;
import com.automation.repository.StepResultRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlockStepRunnerTest {

    @Mock private StepResultRepository stepResultRepository;
    @Mock private ExecutionBroadcaster broadcaster;
    @Mock private ActivityLogService activityLogService;
    @Mock private ContainerRepository containerRepository;
    @Mock private BlockExecutor blockExecutor;

    private BlockStepRunner runner;
    private WorkflowRun run;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        runner = new BlockStepRunner(stepResultRepository, broadcaster, new BlockOutputValidator(),
                activityLogService, containerRepository);
        run = WorkflowRun.builder().id("run-1").status(RunStatus.RUNNING)
                .startedAt(Instant.now()).steps(new ArrayList<>()).build();
        context = ExecutionContext.builder().workflowRunId("run-1").build();
    }

    private static BlockDefinition block(String name, Map<String, Object> validation) {
        Map<String, Object> config = new HashMap<>();
        if (validation != null) config.put("validation", validation);
        return BlockDefinition.builder().type("simple").name(name).config(config).build();
    }

    private static StepResult result(StepStatus status, String output) {
        return StepResult.builder().status(status).input("the input").output(output)
                .completedAt(Instant.parse("2026-09-12T10:00:00Z")).durationMs(42L)
                .metadata(new HashMap<>(Map.of("tokens", 7))).build();
    }

    // ── startStep ──────────────────────────────────────────────────────────

    @Test
    void startStep_persistsRunningPlaceholderAndBroadcasts() {
        StepResult step = runner.startStep(run, 3, block(null, null));

        assertThat(step.getStepIndex()).isEqualTo(3);
        assertThat(step.getStatus()).isEqualTo(StepStatus.RUNNING);
        assertThat(step.getStartedAt()).isNotNull();
        assertThat(step.getMetadata()).isEqualTo(Map.of("blockType", "simple"));
        assertThat(step.getWorkflowRun()).isSameAs(run);
        // the step is part of the run before it is persisted, and it is saved before the broadcast
        assertThat(run.getSteps()).containsExactly(step);
        var order = inOrder(stepResultRepository, broadcaster);
        order.verify(stepResultRepository).save(step);
        order.verify(broadcaster).broadcastStepResult("run-1", step);
    }

    // ── runStep: validation ────────────────────────────────────────────────

    @Test
    void runStep_validationFailsWithoutRetry_marksFailedAndAppendsReason() {
        BlockDefinition block = block("b", Map.of("contains", "OK"));
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "nope"));
        StepResult step = runner.startStep(run, 0, block);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput()).isEqualTo("nope\n[VALIDATION] output missing required substring: OK");
        verify(blockExecutor).execute(eq(block), any());
        verifyNoInteractions(activityLogService);
    }

    @Test
    void runStep_validationFailsThenPasses_retriesAndLogsActivity() {
        BlockDefinition block = block("b", Map.of("contains", "OK", "retry_on_fail", true, "max_retries", 2));
        when(blockExecutor.execute(eq(block), any()))
                .thenReturn(result(StepStatus.COMPLETED, "nope"))
                .thenReturn(result(StepStatus.COMPLETED, "all OK"));
        StepResult step = runner.startStep(run, 0, block);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(step.getOutput()).isEqualTo("all OK");
        verify(activityLogService).warn(LogCategory.EXECUTION,
                "Block 0 validation failed (attempt 1/2): output missing required substring: OK",
                "WorkflowExecutor", "run-1");
    }

    @Test
    void runStep_retriesExhausted_finalCheckMarksFailed() {
        BlockDefinition block = block("b", Map.of("contains", "OK", "retry_on_fail", true, "max_retries", 1));
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "nope"));
        StepResult step = runner.startStep(run, 2, block);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(step.getOutput())
                .isEqualTo("nope\n[VALIDATION] failed after 1 retries: output missing required substring: OK");
        verify(activityLogService).warn(LogCategory.EXECUTION,
                "Block 2 validation failed (attempt 1/1): output missing required substring: OK",
                "WorkflowExecutor", "run-1");
    }

    // ── runStep: result copying ────────────────────────────────────────────

    @Test
    void runStep_copiesOutputIntoContext() {
        BlockDefinition block = block("Report", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "the output"));
        StepResult step = runner.startStep(run, 1, block);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getInput()).isEqualTo("the input");
        assertThat(step.getOutput()).isEqualTo("the output");
        assertThat(step.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(step.getDurationMs()).isEqualTo(42L);
        assertThat(step.getCompletedAt()).isEqualTo(Instant.parse("2026-09-12T10:00:00Z"));
        assertThat(step.getMetadata())
                .containsEntry("tokens", 7)
                .containsEntry("blockType", "simple")
                .containsEntry("blockName", "Report");
        assertThat(context.getPreviousOutput()).isEqualTo("the output");
        assertThat(context.getVariable("step_1_output")).isEqualTo("the output");
        assertThat(context.getBlockOutput("Report")).isEqualTo("the output");
    }

    @Test
    void runStep_unnamedBlock_setsNoBlockOutputAndNoBlockName() {
        BlockDefinition block = block("   ", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, null));
        StepResult step = runner.startStep(run, 0, block);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getMetadata()).doesNotContainKey("blockName");
        assertThat(context.getBlockOutput("   ")).isNull();
        assertThat(context.getVariable("step_0_output")).isEqualTo("");
    }

    @Test
    void runStep_completedStep_setsContextDelta() {
        BlockDefinition block = block("b", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "out"));
        context.setVariable("user_var", "kept");
        run.setInputContext(new HashMap<>(Map.of("from_input", "ignored")));
        context.setVariable("from_input", "ignored");
        StepResult step = runner.startStep(run, 0, block);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getContextDelta()).containsExactly(Map.entry("user_var", "kept"));
    }

    @Test
    void runStep_failedStep_setsNoContextDelta() {
        BlockDefinition block = block("b", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.FAILED, "boom"));
        context.setVariable("user_var", "kept");
        StepResult step = runner.startStep(run, 0, block);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getContextDelta()).isNull();
    }

    // ── runStep: container activity + persist order ────────────────────────

    @Test
    void runStep_broadcastsBeforePersisting() {
        BlockDefinition block = block("b", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "out"));
        StepResult step = runner.startStep(run, 0, block);

        runner.runStep(step, block, blockExecutor, context, run, "container-1");

        var order = inOrder(containerRepository, broadcaster, stepResultRepository);
        order.verify(containerRepository).touchActivity(eq("container-1"), any(Instant.class));
        order.verify(broadcaster).broadcastStepResult("run-1", step);
        order.verify(stepResultRepository).save(step);
    }

    @Test
    void runStep_noContainer_skipsActivity() {
        BlockDefinition block = block("b", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "out"));
        StepResult step = runner.startStep(run, 0, block);

        runner.runStep(step, block, blockExecutor, context, run, null);

        verifyNoInteractions(containerRepository);
        // once for the RUNNING placeholder, once for the finished step
        verify(broadcaster, times(2)).broadcastStepResult("run-1", step);
    }

    @Test
    void updateContainerActivity_repositoryThrows_isSwallowed() {
        when(containerRepository.touchActivity(eq("container-1"), any(Instant.class)))
                .thenThrow(new RuntimeException("db down"));

        runner.updateContainerActivity("container-1");

        verify(containerRepository).touchActivity(eq("container-1"), any(Instant.class));
        verify(broadcaster, never()).broadcastStepResult(any(), any());
    }

    @Test
    void startStep_appendsToExistingSteps() {
        run.setSteps(new ArrayList<>(List.of(StepResult.builder().stepIndex(0).build())));

        StepResult step = runner.startStep(run, 1, block("b", null));

        assertThat(run.getSteps()).hasSize(2);
        assertThat(run.getSteps().get(1)).isSameAs(step);
    }

    // ── runStep: taint propagation (webhook-triggered runs) ────────────────

    @Test
    void runStep_taintedRun_marksOutputAndStepVariableUntrusted() {
        BlockDefinition block = block("report", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "x; id"));
        context.setUntrustedVariable("branch", "main");
        StepResult step = runner.startStep(run, 1, block);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(context.isUntrusted("step_1_output")).isTrue();
        assertThat(context.resolveShellTemplate(
                "{{ previous_output }} {{ block.report.output }} {{ step_1_output }}", false))
                .isEqualTo("'x; id' 'x; id' 'x; id'");
    }

    @Test
    void runStep_taintedRun_contextDeltaCarriesTheTaint() {
        BlockDefinition block = block("report", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "x; id"));
        context.setUntrustedVariable("branch", "main; id");
        run.setInputContext(new HashMap<>(Map.of("branch", "main; id")));
        StepResult step = runner.startStep(run, 0, block);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(step.getContextDelta())
                .containsEntry(ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("branch", "step_0_output"))
                .containsEntry(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE, List.of("previous_output", "report"));
    }

    @Test
    void runStep_cleanRun_addsNoTaintToContextOrDelta() {
        BlockDefinition block = block("report", null);
        when(blockExecutor.execute(eq(block), any())).thenReturn(result(StepStatus.COMPLETED, "a | b"));
        StepResult step = runner.startStep(run, 0, block);

        runner.runStep(step, block, blockExecutor, context, run, null);

        assertThat(context.hasUntrustedInput()).isFalse();
        assertThat(context.resolveShellTemplate("{{ previous_output }} {{ step_0_output }}", false))
                .isEqualTo("a | b a | b");
        assertThat(step.getContextDelta()).isNull();
    }
}
