package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.ai.HostClaudeService.ReasoningResult;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.learning.LearningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgenticBlockExecutorTest {

    @Mock
    private HostClaudeService hostClaude;

    @Mock
    private CommandExecutorService commandExecutor;

    @Mock
    private LearningService learningService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private WorkflowRunRepository runRepository;

    private AgenticBlockExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new AgenticBlockExecutor(hostClaude, commandExecutor, learningService, messagingTemplate, runRepository);

        // Default: resolve run-1 -> workflow-1
        Workflow workflow = Workflow.builder().id("workflow-1").build();
        WorkflowRun run = WorkflowRun.builder().id("run-1").workflow(workflow).build();
        lenient().when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
    }

    private ExecutionContext buildContext() {
        return ExecutionContext.builder()
                .containerId("test-container")
                .workflowRunId("run-1")
                .build();
    }

    private BlockDefinition buildBlock(String goal) {
        return buildBlock(goal, 10);
    }

    private BlockDefinition buildBlock(String goal, int maxIterations) {
        Map<String, Object> config = new HashMap<>();
        config.put("goal", goal);
        config.put("success_criteria", "Test passes");
        config.put("max_iterations", maxIterations);
        config.put("iteration_timeout_seconds", 300);
        config.put("require_approval", false);
        return BlockDefinition.builder()
                .type("agentic")
                .config(config)
                .build();
    }

    private ReasoningResult rr(String text) {
        return new ReasoningResult(text, null);
    }

    @Test
    void supports_returnsTrue_forAgenticType() {
        assertThat(executor.supports("agentic")).isTrue();
    }

    @Test
    void supports_returnsFalse_forOtherTypes() {
        assertThat(executor.supports("simple")).isFalse();
        assertThat(executor.supports("retry")).isFalse();
    }

    @Test
    void execute_goalAchievedOnFirstIteration() throws Exception {
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured("prompt")).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Already done\nACTION:\nSUMMARY: Goal was already met"));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Test goal"), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("ACHIEVED");

        Map<String, Object> metadata = result.getMetadata();
        assertThat(metadata.get("goalAchieved")).isEqualTo(true);
        assertThat(metadata.get("totalIterations")).isEqualTo(1);

        // Should not have called container execution
        verify(commandExecutor, never()).executeCommandEnriched(anyString(), anyString(), anyString(), any());
    }

    @Test
    void execute_multiIterationUntilGoalAchieved() throws Exception {
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");

        when(hostClaude.reasonStructured("prompt"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Need to create file\nACTION: Create test.txt\nSUMMARY: Creating file"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: File created\nACTION:\nSUMMARY: Done"));

        when(commandExecutor.executeCommandEnriched("test-container", "claude", "Create test.txt", null))
                .thenReturn(new CliSession.EnrichedCommandResult("File created", 0, null));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Create a file"), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);

        Map<String, Object> metadata = result.getMetadata();
        assertThat(metadata.get("goalAchieved")).isEqualTo(true);
        assertThat(metadata.get("totalIterations")).isEqualTo(2);
    }

    @Test
    void execute_maxIterationsReached() throws Exception {
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured("prompt")).thenReturn(
                rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Still working\nACTION: Try again\nSUMMARY: Retrying"));

        when(commandExecutor.executeCommandEnriched("test-container", "claude", "Try again", null))
                .thenReturn(new CliSession.EnrichedCommandResult("Still failing", 1, null));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Impossible goal", 2), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);

        Map<String, Object> metadata = result.getMetadata();
        assertThat(metadata.get("goalAchieved")).isEqualTo(false);
        assertThat(metadata.get("totalIterations")).isEqualTo(2);
    }

    @Test
    void execute_cancellationStopsLoop() throws Exception {
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");

        when(commandExecutor.executeCommandEnriched("test-container", "claude", "Do something", null))
                .thenReturn(new CliSession.EnrichedCommandResult("partial", 0, null));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        ExecutionContext context = buildContext();
        // Cancel after first iteration observe
        when(hostClaude.reasonStructured("prompt"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do something\nSUMMARY: Step 1"))
                .thenAnswer(invocation -> {
                    context.cancel();
                    return rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: More\nACTION: More work\nSUMMARY: Step 2");
                });

        StepResult result = executor.execute(buildBlock("Some goal"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    void execute_hostClaudeError_stopsWithFailure() throws Exception {
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured("prompt")).thenThrow(new java.io.IOException("Connection refused"));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Test"), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("REASON ERROR");
    }

    @Test
    void execute_containerCommandFailure_isObservedAndRetried() throws Exception {
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");

        when(hostClaude.reasonStructured("prompt"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: First try\nACTION: Run tests\nSUMMARY: Running tests"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Try different approach\nACTION: Fix and run\nSUMMARY: Fixing"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Fixed\nACTION:\nSUMMARY: All good"));

        when(commandExecutor.executeCommandEnriched("test-container", "claude", "Run tests", null))
                .thenReturn(new CliSession.EnrichedCommandResult("Tests failed: 3 errors", 1, null));
        when(commandExecutor.executeCommandEnriched("test-container", "claude", "Fix and run", null))
                .thenReturn(new CliSession.EnrichedCommandResult("All tests pass", 0, null));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Fix tests", 5), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("Exit code: 1");
        assertThat(result.getOutput()).contains("Exit code: 0");
    }

    @Test
    void execute_metadataStoredCorrectly() throws Exception {
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured("prompt")).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("My goal"), buildContext());

        Map<String, Object> metadata = result.getMetadata();
        assertThat(metadata).containsKey("blockType");
        assertThat(metadata.get("blockType")).isEqualTo("agentic");
        assertThat(metadata).containsKey("goal");
        assertThat(metadata.get("goal")).isEqualTo("My goal");
        assertThat(metadata).containsKey("totalIterations");
        assertThat(metadata).containsKey("goalAchieved");
        assertThat(metadata).containsKey("iterations");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_learningContextIncluded() throws Exception {
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of(
                Map.of("goal", "Similar goal", "totalIterations", 3, "outcome", "COMPLETED",
                        "strategy", "Action A -> Action B")
        ));

        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured("prompt")).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        executor.execute(buildBlock("Test"), buildContext());

        // Verify buildConversationPrompt was called with a system prompt containing learning data
        verify(hostClaude).buildConversationPrompt(contains("Previously Tried Strategies"), anyList());
    }

    @Test
    void execute_learningServiceCalledWithWorkflowId_notRunId() throws Exception {
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured("prompt")).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(learningService.getAgenticStrategyHistory("workflow-1")).thenReturn(List.of());

        executor.execute(buildBlock("Test"), buildContext());

        // Must be called with workflow-1 (the workflow ID), NOT run-1 (the run ID)
        verify(learningService).getAgenticStrategyHistory("workflow-1");
        verify(learningService, never()).getAgenticStrategyHistory("run-1");
    }

    @Test
    void execute_broadcastsWebSocketEvents() throws Exception {
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured("prompt")).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        executor.execute(buildBlock("Test"), buildContext());

        // Should broadcast at least: agentic_started, agentic_reasoning, agentic_reasoned, agentic_goal_achieved, agentic_completed
        verify(messagingTemplate, atLeast(4)).convertAndSend(
                eq("/topic/executions/run-1/logs"),
                any(Map.class));
    }

    @Test
    void execute_goalImpossible_returnsFailed() throws Exception {
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured("prompt")).thenReturn(
                rr("GOAL_STATUS: IMPOSSIBLE\nRATIONALE: No access\nACTION:\nSUMMARY: Cannot proceed"));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Impossible"), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata().get("goalAchieved")).isEqualTo(false);
    }

    @Test
    void execute_missingGoalConfig_throws() {
        BlockDefinition block = BlockDefinition.builder()
                .type("agentic")
                .config(new HashMap<>())
                .build();

        assertThatThrownBy(() -> executor.execute(block, buildContext()))
                .isInstanceOf(com.automation.exception.WorkflowExecutionException.class)
                .hasMessageContaining("goal");
    }

    @Test
    void execute_setsContextVariables() throws Exception {
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured("prompt")).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        ExecutionContext context = buildContext();
        executor.execute(buildBlock("Test"), context);

        assertThat(context.getVariable("agentic_goal_achieved")).isEqualTo(true);
        assertThat(context.getVariable("agentic_iterations")).isEqualTo(1);
    }
}
