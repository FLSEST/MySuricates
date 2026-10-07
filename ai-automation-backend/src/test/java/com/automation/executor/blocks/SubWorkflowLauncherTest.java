package com.automation.executor.blocks;

import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.executor.ExecutionContext;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.RunControlService;
import com.automation.service.workflow.WorkflowContextDataService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubWorkflowLauncherTest {

    private static final Set<String> PLATFORM = Set.of("open_todos", "previous_report");

    @Mock private WorkflowService workflowService;
    @Mock private WorkflowExecutor workflowExecutor;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private StepResultRepository stepResultRepository;
    @Mock private WorkflowContextDataService contextDataService;
    @Mock private RunControlService runControl;

    private SubWorkflowLauncher launcher;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        lenient().when(contextDataService.platformVariableNames()).thenReturn(PLATFORM);
        launcher = new SubWorkflowLauncher(workflowService, workflowExecutor, runRepository,
                stepResultRepository, contextDataService, runControl);
        launcher.pollInterval = Duration.ofMillis(2);
        context = ExecutionContext.builder().workflowRunId("parent-run").build();
    }

    private Workflow child() {
        Workflow child = Workflow.builder().id("child").name("Child").build();
        when(workflowService.findById("child")).thenReturn(child);
        return child;
    }

    private WorkflowRun run(Workflow wf, RunStatus status) {
        return WorkflowRun.builder().id("child-run").workflow(wf).status(status).build();
    }

    @Nested
    class OutcomeRecord {

        @Test
        void should_markARefusedOutcome_asNotStartedAndUnsuccessful() {
            SubWorkflowLauncher.Outcome refused = SubWorkflowLauncher.Outcome.refused("w1", "why");

            assertThat(refused.workflowId()).isEqualTo("w1");
            assertThat(refused.output()).isEqualTo("why");
            assertThat(refused.workflowName()).isNull();
            assertThat(refused.subRunStatus()).isNull();
            assertThat(refused.success()).isFalse();
            assertThat(refused.started()).isFalse();
        }

        @Test
        void should_countAsStarted_onceAChildRunExists() {
            var outcome = new SubWorkflowLauncher.Outcome("w1", "W", "r1", RunStatus.FAILED, "out", false);

            assertThat(outcome.started()).isTrue();
        }
    }

    @Nested
    class ReservedReason {

        @Test
        void should_refuseReservedDoubleUnderscoreKeys() {
            assertThat(SubWorkflowLauncher.reservedReason("__parent_workflow_ids", "item", PLATFORM))
                    .isEqualTo("context may not set the reserved key __parent_workflow_ids");
        }

        @Test
        void should_refusePlatformDataAndRunVariables() {
            assertThat(SubWorkflowLauncher.reservedReason("open_todos", "item", PLATFORM))
                    .isEqualTo("context may not set open_todos — the platform sets it for every run");
            for (String runVariable : SubWorkflowLauncher.RUN_VARIABLES) {
                assertThat(SubWorkflowLauncher.reservedReason(runVariable, "item", PLATFORM))
                        .as(runVariable).contains("the platform sets it for every run");
            }
        }

        @Test
        void should_refuseTheLoopsOwnItemVariables() {
            assertThat(SubWorkflowLauncher.reservedReason("item", "item", PLATFORM))
                    .isEqualTo("context may not set item — the loop sets it per item");
            assertThat(SubWorkflowLauncher.reservedReason("item_index", "item", PLATFORM))
                    .endsWith("the loop sets it per item");
            assertThat(SubWorkflowLauncher.reservedReason("item.id", "item", PLATFORM))
                    .endsWith("the loop sets it per item");
        }

        @Test
        void should_allowOrdinaryNames() {
            assertThat(SubWorkflowLauncher.reservedReason("branch", "item", PLATFORM)).isNull();
            assertThat(SubWorkflowLauncher.reservedReason("items", "item", PLATFORM)).isNull();
            assertThat(SubWorkflowLauncher.reservedReason("_single", "item", PLATFORM)).isNull();
        }
    }

    @Nested
    class ResolveWorkflowId {

        @Test
        void should_returnALiteralIdStripped() {
            StringBuilder refusal = new StringBuilder();

            assertThat(SubWorkflowLauncher.resolveWorkflowId("  wf-1 ", context, refusal)).isEqualTo("wf-1");
            assertThat(refusal).isEmpty();
        }

        @Test
        void should_resolveATrustedVariable() {
            context.setVariable("target", "wf-2");
            StringBuilder refusal = new StringBuilder();

            assertThat(SubWorkflowLauncher.resolveWorkflowId("{{ target }}", context, refusal)).isEqualTo("wf-2");
            assertThat(refusal).isEmpty();
        }

        @Test
        void should_refusePreviousOutputBlockOutputsAndUntrustedVariables() {
            context.setUntrustedVariable("payload_id", "wf-3");
            for (String key : List.of("previous_output", "block.scan.output", "payload_id")) {
                StringBuilder refusal = new StringBuilder();

                assertThat(SubWorkflowLauncher.resolveWorkflowId("{{ " + key + " }}", context, refusal)).as(key).isNull();
                assertThat(refusal.toString())
                        .isEqualTo("workflow_id may only use trusted context variables, not {{ " + key + " }}");
            }
        }

        @Test
        void should_refuse_when_resolutionIsEmptyOrLeavesAPlaceholder() {
            context.setVariable("empty", "  ");
            StringBuilder empty = new StringBuilder();
            assertThat(SubWorkflowLauncher.resolveWorkflowId("{{ empty }}", context, empty)).isNull();
            assertThat(empty.toString()).isEqualTo("workflow_id '{{ empty }}' does not resolve to a workflow id");

            StringBuilder unknown = new StringBuilder();
            assertThat(SubWorkflowLauncher.resolveWorkflowId("{{ unknown }}", context, unknown)).isNull();
            assertThat(unknown.toString()).contains("does not resolve to a workflow id");
        }
    }

    @Nested
    class Run {

        @Test
        void should_refuse_atTheDepthLimit_withoutLookingUpTheWorkflow() {
            context.getVariables().put("__parent_workflow_ids", new ArrayList<>(List.of("a", "b", "c", "d", "e")));

            var outcome = launcher.run("child", context);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.started()).isFalse();
            assertThat(outcome.output()).isEqualTo("Maximum sub-workflow depth (5) exceeded");
            verifyNoInteractions(workflowService, workflowExecutor);
        }

        @Test
        void should_allowOneLevelBelowTheDepthLimit() {
            context.getVariables().put("__parent_workflow_ids", new ArrayList<>(List.of("a", "b", "c", "d")));
            Workflow child = child();
            when(workflowExecutor.execute(eq(child), anyMap(), eq(TriggerSource.SUB_WORKFLOW)))
                    .thenReturn(run(child, RunStatus.RUNNING));
            when(runRepository.findById("child-run")).thenReturn(Optional.of(run(child, RunStatus.COMPLETED)));

            assertThat(launcher.run("child", context).success()).isTrue();
        }

        @Test
        void should_refuseACycle() {
            context.getVariables().put("__parent_workflow_ids", new ArrayList<>(List.of("a", "child")));

            var outcome = launcher.run("child", context);

            assertThat(outcome.output()).isEqualTo("Circular sub-workflow reference detected: child");
            verifyNoInteractions(workflowExecutor);
        }

        @Test
        void should_failWithoutThrowing_when_parentIdsAreNotAListOfStrings() {
            context.getVariables().put("__parent_workflow_ids", List.of("a", 42));

            var outcome = launcher.run("child", context);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("__parent_workflow_ids is not a list of workflow ids");
            verifyNoInteractions(workflowExecutor);
        }

        @Test
        void should_failWithoutName_when_workflowIsUnknown() {
            when(workflowService.findById("child")).thenThrow(new IllegalArgumentException("Workflow not found: child"));

            var outcome = launcher.run("child", context);

            assertThat(outcome.workflowName()).isNull();
            assertThat(outcome.subRunId()).isNull();
            assertThat(outcome.output()).isEqualTo("Sub-workflow execution failed: Workflow not found: child");
        }

        @Test
        void should_keepTheName_when_admissionRefusesTheStart() {
            Workflow child = child();
            when(workflowExecutor.execute(eq(child), anyMap(), eq(TriggerSource.SUB_WORKFLOW)))
                    .thenThrow(new IllegalStateException("refused"));

            var outcome = launcher.run("child", context);

            assertThat(outcome.workflowName()).isEqualTo("Child");
            assertThat(outcome.started()).isFalse();
            assertThat(outcome.output()).isEqualTo("Sub-workflow execution failed: refused");
        }

        @Test
        void should_returnTheChildsLastStepOutput_onSuccess() {
            Workflow child = child();
            when(workflowExecutor.execute(eq(child), anyMap(), eq(TriggerSource.SUB_WORKFLOW)))
                    .thenReturn(run(child, RunStatus.PENDING));
            when(runRepository.findById("child-run")).thenReturn(Optional.of(run(child, RunStatus.COMPLETED)));
            when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("child-run")).thenReturn(List.of(
                    StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("first").build(),
                    StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("last").build()));

            var outcome = launcher.run("child", context);

            assertThat(outcome).isEqualTo(new SubWorkflowLauncher.Outcome(
                    "child", "Child", "child-run", RunStatus.COMPLETED, "last", true));
        }

        @Test
        void should_reportFailure_forAnyEndStatusOtherThanCompleted() {
            Workflow child = child();
            when(workflowExecutor.execute(eq(child), anyMap(), eq(TriggerSource.SUB_WORKFLOW)))
                    .thenReturn(run(child, RunStatus.RUNNING));
            when(runRepository.findById("child-run")).thenReturn(Optional.of(run(child, RunStatus.FAILED)));

            var outcome = launcher.run("child", context);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.started()).isTrue();
            assertThat(outcome.subRunStatus()).isEqualTo(RunStatus.FAILED);
        }

        @Test
        void should_listStepStatuses_when_lastStepHasNoOutput() {
            Workflow child = child();
            when(workflowExecutor.execute(eq(child), anyMap(), eq(TriggerSource.SUB_WORKFLOW)))
                    .thenReturn(run(child, RunStatus.RUNNING));
            when(runRepository.findById("child-run")).thenReturn(Optional.of(run(child, RunStatus.FAILED)));
            when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("child-run")).thenReturn(List.of(
                    StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("z".repeat(600)).build(),
                    StepResult.builder().stepIndex(1).status(StepStatus.FAILED).output(null).build()));

            var outcome = launcher.run("child", context);

            assertThat(outcome.output()).isEqualTo("Sub-workflow completed with status: FAILED\n"
                    + "[Step 0] COMPLETED: " + "z".repeat(500) + "...\n"
                    + "[Step 1] FAILED: \n");
        }

        @Test
        void should_reportOnlyTheStatusLine_when_childHasNoSteps() {
            Workflow child = child();
            when(workflowExecutor.execute(eq(child), anyMap(), eq(TriggerSource.SUB_WORKFLOW)))
                    .thenReturn(run(child, RunStatus.RUNNING));
            when(runRepository.findById("child-run")).thenReturn(Optional.of(run(child, RunStatus.CANCELLED)));
            when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("child-run")).thenReturn(List.of());

            assertThat(launcher.run("child", context).output())
                    .isEqualTo("Sub-workflow completed with status: CANCELLED\n");
        }

        @Test
        @SuppressWarnings("unchecked")
        void should_handTheChildTheParentVariables_withoutPlatformData_andWithTheChain() {
            context.setVariable("branch", "main");
            context.setVariable("open_todos", "a long list");
            context.getVariables().put("__parent_workflow_ids", new ArrayList<>(List.of("root")));
            context.getVariables().put("trigger_source", "SCHEDULE");
            Workflow child = child();
            when(workflowExecutor.execute(eq(child), anyMap(), eq(TriggerSource.SUB_WORKFLOW)))
                    .thenReturn(run(child, RunStatus.RUNNING));
            when(runRepository.findById("child-run")).thenReturn(Optional.of(run(child, RunStatus.COMPLETED)));

            launcher.run("child", context);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(workflowExecutor).execute(eq(child), captor.capture(), eq(TriggerSource.SUB_WORKFLOW));
            Map<String, Object> handed = captor.getValue();
            assertThat(handed)
                    .containsEntry("branch", "main")
                    .doesNotContainKey("open_todos")
                    .containsEntry("previous_output", "")
                    .containsEntry("__parent_workflow_ids", List.of("root", "child"))
                    .containsEntry(TriggerSource.PARENT_CONTEXT_KEY, "SCHEDULE");
            // the parent's own chain list is not modified
            assertThat(context.getVariables().get("__parent_workflow_ids")).isEqualTo(List.of("root"));
        }

        @Test
        @SuppressWarnings("unchecked")
        void should_passThePreviousOutput_andDropAForgedOrigin_when_parentOriginIsUnknown() {
            context.setPreviousOutput("prev");
            context.getVariables().put(TriggerSource.PARENT_CONTEXT_KEY, "MANUAL");   // no trigger_source of its own
            Workflow child = child();
            when(workflowExecutor.execute(eq(child), anyMap(), eq(TriggerSource.SUB_WORKFLOW)))
                    .thenReturn(run(child, RunStatus.RUNNING));
            when(runRepository.findById("child-run")).thenReturn(Optional.of(run(child, RunStatus.COMPLETED)));

            launcher.run("child", context);

            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
            verify(workflowExecutor).execute(eq(child), captor.capture(), eq(TriggerSource.SUB_WORKFLOW));
            assertThat(captor.getValue())
                    .containsEntry("previous_output", "prev")
                    .containsEntry("__parent_workflow_ids", List.of("child"))
                    .doesNotContainKey(TriggerSource.PARENT_CONTEXT_KEY);
        }

        @Test
        void should_cancelTheChildOnlyOnce_when_parentStopsAndTheWaitThenTimesOut() {
            Workflow child = child();
            when(workflowExecutor.execute(eq(child), anyMap(), eq(TriggerSource.SUB_WORKFLOW)))
                    .thenReturn(run(child, RunStatus.RUNNING));
            when(runRepository.findById("child-run")).thenReturn(Optional.of(run(child, RunStatus.RUNNING)));
            context.cancel();
            launcher.maxWait = Duration.ofMillis(30);

            var outcome = launcher.run("child", context);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("timed out");
            verify(runControl, times(1)).cancelExecution("child-run");
        }

        @Test
        void should_stillFailCleanly_when_cancellingTheChildAfterGivingUpThrows() {
            Workflow child = child();
            when(workflowExecutor.execute(eq(child), anyMap(), eq(TriggerSource.SUB_WORKFLOW)))
                    .thenReturn(run(child, RunStatus.RUNNING));
            when(runRepository.findById("child-run")).thenReturn(Optional.of(run(child, RunStatus.RUNNING)));
            doThrow(new IllegalStateException("gone")).when(runControl).cancelExecution("child-run");
            launcher.maxWait = Duration.ofMillis(20);

            var outcome = launcher.run("child", context);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("Sub-workflow timed out");
            verify(runControl).cancelExecution("child-run");
        }

        @Test
        void should_failWithoutThrowing_when_childRunDisappears() {
            Workflow child = child();
            when(workflowExecutor.execute(eq(child), anyMap(), eq(TriggerSource.SUB_WORKFLOW)))
                    .thenReturn(run(child, RunStatus.RUNNING));
            when(runRepository.findById("child-run")).thenReturn(Optional.empty());

            var outcome = launcher.run("child", context);

            assertThat(outcome.success()).isFalse();
            assertThat(outcome.output()).contains("disappeared while waiting");
        }
    }

    @Test
    void should_delegatePlatformVariableNamesToTheContextDataService() {
        assertThat(launcher.platformVariableNames()).isEqualTo(PLATFORM);
        verify(contextDataService).platformVariableNames();
    }

    @Test
    void should_capTheDepthAtFive() {
        assertThat(SubWorkflowLauncher.MAX_DEPTH).isEqualTo(5);
    }
}
