package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.executor.ExecutionContext;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.dlq.DeadLetterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RunCompletionHandlerTest {

    @Mock private TemplateVerificationService templateVerificationService;
    @Mock private ActivityLogService activityLogService;
    @Mock private DeadLetterService deadLetterService;

    @InjectMocks private RunCompletionHandler handler;

    private ExecutionContext ctx;

    @BeforeEach
    void setUp() {
        ctx = ExecutionContext.builder().workflowRunId("run-1").build();
    }

    /** Builds a run with one COMPLETED step per output, in order. */
    private static WorkflowRun run(RunStatus status, String... stepOutputs) {
        List<StepResult> steps = new ArrayList<>();
        for (int i = 0; i < stepOutputs.length; i++) {
            steps.add(StepResult.builder().stepIndex(i).status(StepStatus.COMPLETED).output(stepOutputs[i]).build());
        }
        return WorkflowRun.builder().id("run-1").status(status).steps(steps).build();
    }

    @Test
    void complete_runningRun_becomesCompleted_andLogsInfo() {
        WorkflowRun run = run(RunStatus.RUNNING, "plain output");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1");

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(activityLogService).info(eq(LogCategory.EXECUTION), contains("completed"),
                eq("WorkflowExecutor"), eq("run-1"), eq("wf-1"));
        verifyNoInteractions(deadLetterService, templateVerificationService);
        assertThat(ctx.getRunMetadata()).doesNotContainKeys("gate_status", "todo_items");
    }

    @Test
    @SuppressWarnings("unchecked")
    void complete_highestGateWins_andSummaryAggregatesAllGates() {
        WorkflowRun run = run(RunStatus.RUNNING,
                "gate_status: GREEN\ngate_category: tests",
                "gate_status: RED\ngate_category: security\ngate_description: leaked key",
                "gate_status: YELLOW\ngate_category: lint");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1");

        assertThat(ctx.getRunMetadata())
                .containsEntry("gate_status", "RED")
                .containsEntry("gate_category", "security")
                .containsEntry("gate_description", "leaked key");
        Map<String, Object> summary = (Map<String, Object>) ctx.getRunMetadata().get("gate_summary");
        assertThat(summary).containsEntry("highest", "RED");
        Map<String, Integer> counts = (Map<String, Integer>) summary.get("counts_by_status");
        assertThat(counts).containsEntry("GREEN", 1).containsEntry("RED", 1).containsEntry("YELLOW", 1);
        assertThat((List<Map<String, Object>>) summary.get("items")).hasSize(3);
        verify(activityLogService).warn(eq(LogCategory.EXECUTION), contains("critical findings"),
                eq("WorkflowExecutor"), eq("run-1"), eq("wf-1"));
    }

    @Test
    void complete_yellowGate_logsWarning_butRunIsCompleted() {
        WorkflowRun run = run(RunStatus.RUNNING, "gate_status: YELLOW\ngate_category: lint");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1");

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(activityLogService).warn(eq(LogCategory.EXECUTION), contains("with warnings"),
                eq("WorkflowExecutor"), eq("run-1"), eq("wf-1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void complete_todoMarkers_collectedIntoRunMetadata() {
        WorkflowRun run = run(RunStatus.RUNNING,
                "todo_item: Fix login | HIGH | backend,bug | Login breaks on Safari",
                "todo_item: Tidy docs | LOW | documentation");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1");

        List<Map<String, Object>> items = (List<Map<String, Object>>) ctx.getRunMetadata().get("todo_items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("title", "Fix login").containsEntry("priority", "HIGH")
                .containsEntry("description", "Login breaks on Safari");
        assertThat(items.get(1)).containsEntry("title", "Tidy docs").doesNotContainKey("description");
    }

    @Test
    void complete_withTemplateId_recordsVerification_andSwallowsFailures() {
        WorkflowRun run = run(RunStatus.RUNNING, "ok");
        doThrow(new RuntimeException("boom")).when(templateVerificationService)
                .recordSuccessfulRun("wf-1", "tpl-1", "proj-1", "run-1");

        assertThatCode(() -> handler.complete(run, ctx, "wf-1", "Demo", "tpl-1", "proj-1"))
                .doesNotThrowAnyException();

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        verify(templateVerificationService).recordSuccessfulRun("wf-1", "tpl-1", "proj-1", "run-1");
    }

    @Test
    void complete_blankTemplateId_doesNotRecordVerification() {
        WorkflowRun run = run(RunStatus.RUNNING, "ok");

        handler.complete(run, ctx, "wf-1", "Demo", "   ", "proj-1");

        verifyNoInteractions(templateVerificationService);
    }

    @Test
    void complete_failedRun_logsError_andRecordsDeadLetter() {
        WorkflowRun run = run(RunStatus.FAILED, "boom");

        handler.complete(run, ctx, "wf-1", "Demo", "tpl-1", "proj-1");

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        verify(activityLogService).error(eq(LogCategory.EXECUTION), contains("failed"),
                eq("WorkflowExecutor"), eq("run-1"), eq("wf-1"));
        verify(deadLetterService).recordFailure(run);
        verifyNoInteractions(templateVerificationService);
    }

    @Test
    void complete_deadLetterThrows_doesNotPropagate() {
        WorkflowRun run = run(RunStatus.FAILED, "boom");
        doThrow(new RuntimeException("dlq down")).when(deadLetterService).recordFailure(run);

        assertThatCode(() -> handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1"))
                .doesNotThrowAnyException();
    }

    @Test
    void complete_cancelledRun_isLeftUntouched() {
        WorkflowRun run = run(RunStatus.CANCELLED, "partial");

        handler.complete(run, ctx, "wf-1", "Demo", null, "proj-1");

        assertThat(run.getStatus()).isEqualTo(RunStatus.CANCELLED);
        verifyNoInteractions(deadLetterService, templateVerificationService, activityLogService);
    }
}
