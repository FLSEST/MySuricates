package com.automation.service.conversion;

import com.automation.dto.response.WorkflowResponse;
import com.automation.entity.*;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.WorkflowMapper;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.workflow.AgentInstructionsService;
import com.automation.service.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowConversionServiceTest {

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private WorkflowService workflowService;

    @Mock
    private WorkflowMapper workflowMapper;

    @Mock
    private AiProviderRegistry aiProviderRegistry;

    @Mock
    private AgentInstructionsService agentInstructionsService;

    @Mock
    private AiProvider aiProvider;

    private WorkflowConversionService conversionService;

    @BeforeEach
    void setUp() throws Exception {
        conversionService = new WorkflowConversionService(
                runRepository, workflowService, workflowMapper, aiProviderRegistry, agentInstructionsService);
        lenient().when(agentInstructionsService.buildInstructionPrefix(null)).thenReturn("");
        lenient().when(aiProviderRegistry.getDefault()).thenReturn(aiProvider);
    }

    private WorkflowRun buildCompletedRunWithAgenticSteps() {
        // Build iteration data that the rule-based converter expects
        List<Map<String, Object>> iterations = List.of(
                Map.of("action", "npm install", "exitCode", 0, "output", "installed"),
                Map.of("action", "npm test", "exitCode", 0, "output", "tests passed")
        );

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("blockType", "agentic");
        metadata.put("goalAchieved", true);
        metadata.put("goal", "Run project tests");
        metadata.put("iterations", iterations);

        StepResult agenticStep = StepResult.builder()
                .id("step-1")
                .status(StepStatus.COMPLETED)
                .metadata(metadata)
                .build();

        Workflow workflow = Workflow.builder().id("wf-1").build();
        WorkflowRun run = WorkflowRun.builder()
                .id("run-1")
                .workflow(workflow)
                .status(RunStatus.COMPLETED)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .steps(new ArrayList<>(List.of(agenticStep)))
                .build();
        agenticStep.setWorkflowRun(run);
        return run;
    }

    @Test
    void suggestWorkflow_completedRunWithAgenticSteps_returnsSuggestion() throws Exception {
        WorkflowRun run = buildCompletedRunWithAgenticSteps();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        // AI suggestion fails, so rule-based kicks in
        when(aiProvider.reason(any())).thenThrow(new RuntimeException("no AI"));

        WorkflowSuggestion suggestion = conversionService.suggestWorkflow("run-1");

        assertThat(suggestion.available()).isTrue();
        assertThat(suggestion.blocks()).hasSize(2);
        assertThat(suggestion.blocks().get(0).getType()).isEqualTo("simple");
        assertThat(suggestion.blocks().get(0).getConfig().get("command")).isEqualTo("npm install");
        assertThat(suggestion.blocks().get(1).getConfig().get("command")).isEqualTo("npm test");
        assertThat(suggestion.originalGoal()).isEqualTo("Run project tests");
        assertThat(suggestion.agenticStepsAnalyzed()).isEqualTo(2);
        assertThat(suggestion.suggestedName()).startsWith("Auto:");
    }

    @Test
    void suggestWorkflow_noAgenticSteps_returnsNotAvailable() {
        // Run with a non-agentic step
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("blockType", "simple");

        StepResult simpleStep = StepResult.builder()
                .id("step-1")
                .status(StepStatus.COMPLETED)
                .metadata(metadata)
                .build();

        Workflow workflow = Workflow.builder().id("wf-1").build();
        WorkflowRun run = WorkflowRun.builder()
                .id("run-2")
                .workflow(workflow)
                .status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(simpleStep)))
                .build();

        when(runRepository.findById("run-2")).thenReturn(Optional.of(run));

        WorkflowSuggestion suggestion = conversionService.suggestWorkflow("run-2");

        assertThat(suggestion.available()).isFalse();
        assertThat(suggestion.blocks()).isEmpty();
    }

    @Test
    void suggestWorkflow_failedRun_returnsNotAvailable() {
        Workflow workflow = Workflow.builder().id("wf-1").build();
        WorkflowRun run = WorkflowRun.builder()
                .id("run-3")
                .workflow(workflow)
                .status(RunStatus.FAILED)
                .steps(new ArrayList<>())
                .build();

        when(runRepository.findById("run-3")).thenReturn(Optional.of(run));

        WorkflowSuggestion suggestion = conversionService.suggestWorkflow("run-3");

        assertThat(suggestion.available()).isFalse();
    }

    @Test
    void suggestWorkflow_runNotFound_throwsResourceNotFound() {
        when(runRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversionService.suggestWorkflow("unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void suggestWorkflowRuleBased_extractsActionsAsSimpleBlocks() throws Exception {
        // Build run with multiple iterations, some with failures (exit != 0)
        List<Map<String, Object>> iterations = new ArrayList<>();
        iterations.add(new HashMap<>(Map.of("action", "echo hello", "exitCode", 0)));
        iterations.add(new HashMap<>(Map.of("action", "bad command", "exitCode", 1)));
        iterations.add(new HashMap<>(Map.of("action", "echo world", "exitCode", 0)));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("blockType", "agentic");
        metadata.put("goalAchieved", true);
        metadata.put("goal", "Print messages");
        metadata.put("iterations", iterations);

        StepResult step = StepResult.builder()
                .id("step-1")
                .status(StepStatus.COMPLETED)
                .metadata(metadata)
                .build();

        Workflow workflow = Workflow.builder().id("wf-1").build();
        WorkflowRun run = WorkflowRun.builder()
                .id("run-4")
                .workflow(workflow)
                .status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(step)))
                .build();

        when(runRepository.findById("run-4")).thenReturn(Optional.of(run));
        // AI fails → rule-based fallback
        when(aiProvider.reason(any())).thenThrow(new RuntimeException("no AI"));

        WorkflowSuggestion suggestion = conversionService.suggestWorkflow("run-4");

        assertThat(suggestion.available()).isTrue();
        // Only successful actions (exit 0) become blocks
        assertThat(suggestion.blocks()).hasSize(2);
        assertThat(suggestion.blocks().get(0).getConfig().get("command")).isEqualTo("echo hello");
        assertThat(suggestion.blocks().get(1).getConfig().get("command")).isEqualTo("echo world");
        // Each block has on_failure=stop
        assertThat(suggestion.blocks().get(0).getConfig().get("on_failure")).isEqualTo("stop");
    }

    @Test
    void createWorkflowFromSuggestion_createsWorkflowViaService() throws Exception {
        WorkflowRun run = buildCompletedRunWithAgenticSteps();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(aiProvider.reason(any())).thenThrow(new RuntimeException("no AI"));

        Workflow createdWorkflow = Workflow.builder()
                .id("new-wf")
                .name("My Workflow")
                .build();
        when(workflowService.create(any())).thenReturn(createdWorkflow);

        WorkflowResponse expectedResponse = new WorkflowResponse(
                "new-wf", "proj-1", "Project", null, null, "My Workflow",
                null, null, List.of(), null, Map.of(), List.of(), 0, null, null, null, null, Map.of(), null, null, null, null, null);
        when(workflowMapper.toResponse(createdWorkflow)).thenReturn(expectedResponse);

        WorkflowResponse result = conversionService.createWorkflowFromSuggestion("run-1", "proj-1", "My Workflow");

        assertThat(result.id()).isEqualTo("new-wf");
        assertThat(result.name()).isEqualTo("My Workflow");

        ArgumentCaptor<com.automation.dto.request.CreateWorkflowRequest> captor =
                ArgumentCaptor.forClass(com.automation.dto.request.CreateWorkflowRequest.class);
        verify(workflowService).create(captor.capture());

        var request = captor.getValue();
        assertThat(request.projectId()).isEqualTo("proj-1");
        assertThat(request.name()).isEqualTo("My Workflow");
        assertThat(request.blocks()).hasSize(2);
    }
}
