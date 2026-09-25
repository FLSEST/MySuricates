package com.automation.executor.blocks;

import com.automation.entity.*;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.AuditContextSource;
import com.automation.service.workflow.GoalContextSource;
import com.automation.service.workflow.WorkflowContextDataService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubWorkflowBlockExecutorTest {

    @Mock
    private WorkflowService workflowService;

    @Mock
    private WorkflowExecutor workflowExecutor;

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private StepResultRepository stepResultRepository;

    @Mock
    private WorkflowContextDataService contextDataService;

    /** What the real collector reserves (pinned against the sources in PlatformVariableSourceWiringTest). */
    private static final Set<String> PLATFORM_NAMES = platformNames();

    private SubWorkflowBlockExecutor executor;

    private ExecutionContext context;

    private static Set<String> platformNames() {
        Set<String> names = new HashSet<>(Set.of("previous_report", "open_todos", "request_metrics",
                AuditContextSource.VARIABLE));
        names.addAll(GoalContextSource.VARIABLES);
        return Set.copyOf(names);
    }

    @BeforeEach
    void setUp() {
        lenient().when(contextDataService.platformVariableNames()).thenReturn(PLATFORM_NAMES);
        executor = new SubWorkflowBlockExecutor(workflowService, workflowExecutor, runRepository, stepResultRepository,
                contextDataService);
        context = ExecutionContext.builder()
                .containerId("container-1")
                .workflowRunId("run-1")
                .build();
    }

    @Test
    void supports_subWorkflow_returnsTrue() {
        assertThat(executor.supports("sub-workflow")).isTrue();
    }

    @Test
    void supports_other_returnsFalse() {
        assertThat(executor.supports("simple")).isFalse();
        assertThat(executor.supports("shell")).isFalse();
    }

    @Test
    void execute_missingWorkflowId_throwsException() {
        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>())
                .build();

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("workflow_id");
    }

    @Test
    void execute_maxDepthExceeded_returnsFailed() {
        List<String> parentIds = new ArrayList<>(List.of("w1", "w2", "w3", "w4", "w5"));
        context.getVariables().put("__parent_workflow_ids", parentIds);

        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", "w6")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("Maximum sub-workflow depth");
    }

    @Test
    void execute_circularReference_returnsFailed() {
        List<String> parentIds = new ArrayList<>(List.of("w1", "w2"));
        context.getVariables().put("__parent_workflow_ids", parentIds);

        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", "w1")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("Circular");
    }

    @Test
    void execute_success_returnsCompleted() {
        Workflow subWorkflow = Workflow.builder()
                .id("sub-wf-1")
                .name("Sub Workflow")
                .build();
        when(workflowService.findById("sub-wf-1")).thenReturn(subWorkflow);

        WorkflowRun subRun = WorkflowRun.builder()
                .id("sub-run-1")
                .workflow(subWorkflow)
                .status(RunStatus.COMPLETED)
                .build();
        StepResult subStep = StepResult.builder()
                .stepIndex(0)
                .status(StepStatus.COMPLETED)
                .output("sub-output")
                .build();
        subRun.getSteps().add(subStep);

        when(workflowExecutor.execute(eq(subWorkflow), any(Map.class), eq(TriggerSource.SUB_WORKFLOW))).thenReturn(subRun);
        when(runRepository.findById("sub-run-1")).thenReturn(Optional.of(subRun));
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("sub-run-1"))
                .thenReturn(List.of(subStep));

        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", "sub-wf-1")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("sub-output");
        assertThat(result.getMetadata()).containsEntry("subWorkflowId", "sub-wf-1");
        assertThat(result.getMetadata()).containsEntry("subRunId", "sub-run-1");
    }

    /** The step only copies the child's output; gate_status/todo_item stay the child run's business (6e5d6e2c). */
    @Test
    void execute_success_marksTheStepAsDerivedOutput() {
        Workflow subWorkflow = Workflow.builder().id("sub-wf-1").name("Sub Workflow").build();
        when(workflowService.findById("sub-wf-1")).thenReturn(subWorkflow);

        WorkflowRun subRun = WorkflowRun.builder()
                .id("sub-run-1").workflow(subWorkflow).status(RunStatus.COMPLETED).build();
        StepResult subStep = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED)
                .output("gate_status: RED\ntodo_item: Child finding | HIGH | bug | desc")
                .build();
        subRun.getSteps().add(subStep);
        when(workflowExecutor.execute(eq(subWorkflow), any(Map.class), eq(TriggerSource.SUB_WORKFLOW))).thenReturn(subRun);
        when(runRepository.findById("sub-run-1")).thenReturn(Optional.of(subRun));
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("sub-run-1")).thenReturn(List.of(subStep));

        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", "sub-wf-1")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getOutput()).contains("gate_status: RED");
        assertThat(result.getMetadata()).containsEntry("derivedOutput", true);
    }

    @Test
    void execute_subWorkflowFails_stillMarksTheStepAsDerivedOutput() {
        Workflow subWorkflow = Workflow.builder().id("sub-wf-1").name("Sub Workflow").build();
        when(workflowService.findById("sub-wf-1")).thenReturn(subWorkflow);
        WorkflowRun subRun = WorkflowRun.builder()
                .id("sub-run-1").workflow(subWorkflow).status(RunStatus.FAILED).build();
        when(workflowExecutor.execute(eq(subWorkflow), any(Map.class), eq(TriggerSource.SUB_WORKFLOW))).thenReturn(subRun);
        when(runRepository.findById("sub-run-1")).thenReturn(Optional.of(subRun));

        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", "sub-wf-1")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata()).containsEntry("derivedOutput", true);
    }

    @Test
    void execute_subWorkflowFails_returnsFailed() {
        Workflow subWorkflow = Workflow.builder()
                .id("sub-wf-1")
                .name("Sub Workflow")
                .build();
        when(workflowService.findById("sub-wf-1")).thenReturn(subWorkflow);

        WorkflowRun subRun = WorkflowRun.builder()
                .id("sub-run-1")
                .workflow(subWorkflow)
                .status(RunStatus.FAILED)
                .build();

        when(workflowExecutor.execute(eq(subWorkflow), any(Map.class), eq(TriggerSource.SUB_WORKFLOW))).thenReturn(subRun);
        when(runRepository.findById("sub-run-1")).thenReturn(Optional.of(subRun));

        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", "sub-wf-1")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_passesParentContextToChild() {
        context.getVariables().put("my_var", "my_value");
        context.setPreviousOutput("prev-output");

        Workflow subWorkflow = Workflow.builder()
                .id("sub-wf-1")
                .name("Sub Workflow")
                .build();
        when(workflowService.findById("sub-wf-1")).thenReturn(subWorkflow);

        WorkflowRun subRun = WorkflowRun.builder()
                .id("sub-run-1")
                .workflow(subWorkflow)
                .status(RunStatus.COMPLETED)
                .build();
        when(workflowExecutor.execute(eq(subWorkflow), any(Map.class), eq(TriggerSource.SUB_WORKFLOW))).thenReturn(subRun);
        when(runRepository.findById("sub-run-1")).thenReturn(Optional.of(subRun));

        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", "sub-wf-1")))
                .build();

        executor.execute(block, context);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(workflowExecutor).execute(eq(subWorkflow), captor.capture(), eq(TriggerSource.SUB_WORKFLOW));

        Map<String, Object> childContext = captor.getValue();
        assertThat(childContext).containsEntry("my_var", "my_value");
        assertThat(childContext).containsEntry("previous_output", "prev-output");
        assertThat((List<String>) childContext.get("__parent_workflow_ids")).contains("sub-wf-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_childInheritsTheParentsEffectiveOrigin() {
        // parent is a chain entry started by a schedule
        context.getVariables().put("trigger_source", "CHAIN");
        context.getVariables().put(TriggerSource.PARENT_CONTEXT_KEY, "SCHEDULE");
        Workflow subWorkflow = Workflow.builder().id("sub-wf-1").name("Sub Workflow").build();
        when(workflowService.findById("sub-wf-1")).thenReturn(subWorkflow);
        WorkflowRun subRun = WorkflowRun.builder().id("sub-run-1").workflow(subWorkflow).status(RunStatus.COMPLETED).build();
        when(workflowExecutor.execute(eq(subWorkflow), any(Map.class), eq(TriggerSource.SUB_WORKFLOW))).thenReturn(subRun);
        when(runRepository.findById("sub-run-1")).thenReturn(Optional.of(subRun));
        BlockDefinition block = BlockDefinition.builder().type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", "sub-wf-1"))).build();

        executor.execute(block, context);
        context.getVariables().put("trigger_source", "bogus");
        executor.execute(block, context);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(workflowExecutor, org.mockito.Mockito.times(2)).execute(eq(subWorkflow), captor.capture(), eq(TriggerSource.SUB_WORKFLOW));
        assertThat(captor.getAllValues().get(0)).containsEntry(TriggerSource.PARENT_CONTEXT_KEY, "SCHEDULE");
        // unreadable origin: the key is dropped so the child counts as unattended
        assertThat(captor.getAllValues().get(1)).doesNotContainKey(TriggerSource.PARENT_CONTEXT_KEY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_taintedParent_handsTheTaintToTheChild() {
        context.setUntrustedVariable("branch", "main; id");
        context.recordOutput("scan", "out");

        Workflow subWorkflow = Workflow.builder().id("sub-wf-1").name("Sub Workflow").build();
        when(workflowService.findById("sub-wf-1")).thenReturn(subWorkflow);
        WorkflowRun subRun = WorkflowRun.builder()
                .id("sub-run-1").workflow(subWorkflow).status(RunStatus.COMPLETED).build();
        when(workflowExecutor.execute(eq(subWorkflow), any(Map.class), eq(TriggerSource.SUB_WORKFLOW))).thenReturn(subRun);
        when(runRepository.findById("sub-run-1")).thenReturn(Optional.of(subRun));

        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", "sub-wf-1")))
                .build();

        executor.execute(block, context);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(workflowExecutor).execute(eq(subWorkflow), captor.capture(), eq(TriggerSource.SUB_WORKFLOW));
        assertThat(captor.getValue())
                .containsEntry(ExecutionContext.UNTRUSTED_KEYS_VARIABLE, List.of("branch"))
                .containsEntry(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE,
                        List.of(ExecutionContext.PREVIOUS_OUTPUT_NAME, "scan"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_cleanParent_handsNoTaintKeysToTheChild() {
        context.recordOutput("scan", "out");

        Workflow subWorkflow = Workflow.builder().id("sub-wf-1").name("Sub Workflow").build();
        when(workflowService.findById("sub-wf-1")).thenReturn(subWorkflow);
        WorkflowRun subRun = WorkflowRun.builder()
                .id("sub-run-1").workflow(subWorkflow).status(RunStatus.COMPLETED).build();
        when(workflowExecutor.execute(eq(subWorkflow), any(Map.class), eq(TriggerSource.SUB_WORKFLOW))).thenReturn(subRun);
        when(runRepository.findById("sub-run-1")).thenReturn(Optional.of(subRun));

        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", "sub-wf-1")))
                .build();

        executor.execute(block, context);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(workflowExecutor).execute(eq(subWorkflow), captor.capture(), eq(TriggerSource.SUB_WORKFLOW));
        assertThat(captor.getValue()).doesNotContainKeys(
                ExecutionContext.UNTRUSTED_KEYS_VARIABLE, ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE);
    }

    /** TODO 22e6bec8: an output tainted by platform data in an AI prompt reaches the child too. */
    @Test
    @SuppressWarnings("unchecked")
    void execute_outputTaintedByAPlatformPrompt_reachesTheChild() {
        context.setUntrustedPlatformVariable("previous_report", "letzter Bericht");
        context.resolvePromptTemplate("Bewerte: {{ previous_report }}");
        context.recordOutput("analyse", "a | b");

        Workflow subWorkflow = Workflow.builder().id("sub-wf-1").name("Sub Workflow").build();
        when(workflowService.findById("sub-wf-1")).thenReturn(subWorkflow);
        WorkflowRun subRun = WorkflowRun.builder()
                .id("sub-run-1").workflow(subWorkflow).status(RunStatus.COMPLETED).build();
        when(workflowExecutor.execute(eq(subWorkflow), any(Map.class), eq(TriggerSource.SUB_WORKFLOW))).thenReturn(subRun);
        when(runRepository.findById("sub-run-1")).thenReturn(Optional.of(subRun));

        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", "sub-wf-1")))
                .build();

        executor.execute(block, context);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(workflowExecutor).execute(eq(subWorkflow), captor.capture(), eq(TriggerSource.SUB_WORKFLOW));
        assertThat(captor.getValue()).containsEntry(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE,
                List.of("analyse", ExecutionContext.PREVIOUS_OUTPUT_NAME));
    }

    /** Runs the sub-workflow block against a completed child and returns the context handed to it. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> childContextOf(ExecutionContext parent) {
        Workflow subWorkflow = Workflow.builder().id("sub-wf-1").name("Sub Workflow").build();
        when(workflowService.findById("sub-wf-1")).thenReturn(subWorkflow);
        WorkflowRun subRun = WorkflowRun.builder()
                .id("sub-run-1").workflow(subWorkflow).status(RunStatus.COMPLETED).build();
        when(workflowExecutor.execute(eq(subWorkflow), any(Map.class), eq(TriggerSource.SUB_WORKFLOW))).thenReturn(subRun);
        when(runRepository.findById("sub-run-1")).thenReturn(Optional.of(subRun));
        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", "sub-wf-1")))
                .build();

        executor.execute(block, parent);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(workflowExecutor).execute(eq(subWorkflow), captor.capture(), eq(TriggerSource.SUB_WORKFLOW));
        return captor.getValue();
    }

    /**
     * TODO 2a0695ff: The parent's platform data does not go into the child's input context — it would be stored
     * with the child run, and the child gets its own injected anyway. Its own variables, goal_id included (the
     * child's goal variables come from it), do go.
     */
    @Test
    void execute_handsTheParentsVariablesToTheChild_butNotItsPlatformData() {
        context.setUntrustedPlatformVariable("previous_report", "Bericht des Elternlaufs");
        context.setUntrustedPlatformVariable("open_todos", "<open_todos>\n- t-1 [HIGH] Titel\n</open_todos>");
        context.setUntrustedPlatformVariable("request_metrics", "FAKT request_metrics_routes_measured: 12");
        context.setUntrustedPlatformVariable("audit_findings", "<audit_findings>\n</audit_findings>");
        context.setUntrustedPlatformVariable("goal_status", "Ziel im Plan");
        context.setVariable("goal_id", "g-1");
        context.setVariable("my_var", "my_value");

        Map<String, Object> child = childContextOf(context);

        assertThat(child).doesNotContainKeys(PLATFORM_NAMES.toArray(String[]::new))
                .containsEntry("goal_id", "g-1")
                .containsEntry("my_var", "my_value");
        assertThat(child).as("a clean parent hands over no taint").doesNotContainKeys(
                ExecutionContext.PLATFORM_IN_SHELL_VARIABLE, ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE);
    }

    /**
     * The values stay behind, their taint does not: platform data the parent put into a shell command line or into
     * the prompt behind the output it passes on still colours the child.
     */
    @Test
    @SuppressWarnings("unchecked")
    void execute_platformDataTheParentUsed_stillTaintsTheChild_withoutItsValues() {
        context.setUntrustedPlatformVariable("previous_report", "Bericht; rm -rf /");
        context.resolveShellTemplate("echo {{ previous_report }}", false);
        context.resolvePromptTemplate("Bewerte: {{ previous_report }}");
        context.recordOutput("bewertung", "a | b");

        Map<String, Object> child = childContextOf(context);

        assertThat(child).doesNotContainKey("previous_report")
                .containsEntry(ExecutionContext.PLATFORM_IN_SHELL_VARIABLE, true)
                .containsEntry("previous_output", "a | b");
        assertThat((List<String>) child.get(ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE))
                .contains("bewertung", ExecutionContext.PREVIOUS_OUTPUT_NAME);
    }
}
