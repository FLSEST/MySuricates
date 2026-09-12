package com.automation.executor.blocks;

import com.automation.entity.*;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRunRepository;
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

    private SubWorkflowBlockExecutor executor;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        executor = new SubWorkflowBlockExecutor(workflowService, workflowExecutor, runRepository, stepResultRepository);
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

        when(workflowExecutor.execute(eq(subWorkflow), any(Map.class))).thenReturn(subRun);
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

        when(workflowExecutor.execute(eq(subWorkflow), any(Map.class))).thenReturn(subRun);
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
        when(workflowExecutor.execute(eq(subWorkflow), any(Map.class))).thenReturn(subRun);
        when(runRepository.findById("sub-run-1")).thenReturn(Optional.of(subRun));

        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", "sub-wf-1")))
                .build();

        executor.execute(block, context);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(workflowExecutor).execute(eq(subWorkflow), captor.capture());

        Map<String, Object> childContext = captor.getValue();
        assertThat(childContext).containsEntry("my_var", "my_value");
        assertThat(childContext).containsEntry("previous_output", "prev-output");
        assertThat((List<String>) childContext.get("__parent_workflow_ids")).contains("sub-wf-1");
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
        when(workflowExecutor.execute(eq(subWorkflow), any(Map.class))).thenReturn(subRun);
        when(runRepository.findById("sub-run-1")).thenReturn(Optional.of(subRun));

        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", "sub-wf-1")))
                .build();

        executor.execute(block, context);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(workflowExecutor).execute(eq(subWorkflow), captor.capture());
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
        when(workflowExecutor.execute(eq(subWorkflow), any(Map.class))).thenReturn(subRun);
        when(runRepository.findById("sub-run-1")).thenReturn(Optional.of(subRun));

        BlockDefinition block = BlockDefinition.builder()
                .type("sub-workflow")
                .config(new HashMap<>(Map.of("workflow_id", "sub-wf-1")))
                .build();

        executor.execute(block, context);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(workflowExecutor).execute(eq(subWorkflow), captor.capture());
        assertThat(captor.getValue()).doesNotContainKeys(
                ExecutionContext.UNTRUSTED_KEYS_VARIABLE, ExecutionContext.UNTRUSTED_OUTPUTS_VARIABLE);
    }
}
