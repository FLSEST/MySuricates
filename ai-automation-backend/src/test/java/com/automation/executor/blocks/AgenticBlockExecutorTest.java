package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.ai.HostClaudeService.ReasoningResult;
import com.automation.service.cli.ClaudeJsonResponse;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.learning.LearningService;
import com.automation.service.system.SystemIdentityService;
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
import static org.mockito.ArgumentMatchers.*;
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

    @Mock
    private SystemIdentityService systemIdentity;

    private AgenticBlockExecutor executor;

    @BeforeEach
    void setUp() {
        lenient().when(systemIdentity.getVersionedName()).thenReturn("MySuricates v0.2.3");
        executor = new AgenticBlockExecutor(hostClaude, commandExecutor, learningService, messagingTemplate, runRepository, systemIdentity);

        // Default: resolve run-1 -> workflow-1
        Workflow workflow = Workflow.builder().id("workflow-1").build();
        WorkflowRun run = WorkflowRun.builder().id("run-1").workflow(workflow).build();
        lenient().when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
    }

    private ExecutionContext buildContext() {
        ExecutionContext ctx = ExecutionContext.builder()
                .containerId("test-container")
                .workflowRunId("run-1")
                .build();
        ctx.setProjectPath("/test/project");
        return ctx;
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
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull())).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Already done\nACTION:\nSUMMARY: Goal was already met"));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Test goal"), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("ACHIEVED");

        Map<String, Object> metadata = result.getMetadata();
        assertThat(metadata.get("goalAchieved")).isEqualTo(true);
        assertThat(metadata.get("totalIterations")).isEqualTo(1);

        // Should not have called container execution
        verify(commandExecutor, never()).executeCommandOnHost(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void execute_multiIterationUntilGoalAchieved() throws Exception {
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");

        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Need to create file\nACTION: Create test.txt\nSUMMARY: Creating file"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: File created\nACTION:\nSUMMARY: Done"));

        when(commandExecutor.executeCommandOnHost("/test/project", "Create test.txt", null, null, null))
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
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull())).thenReturn(
                rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Still working\nACTION: Try again\nSUMMARY: Retrying"));

        when(commandExecutor.executeCommandOnHost("/test/project", "Try again", null, null, null))
                .thenReturn(new CliSession.EnrichedCommandResult("Still failing", 1, null));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        // Use 3 iterations to avoid repeated action detection (default max_repeated_actions=3 would trigger at 2 iterations with same action)
        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Impossible goal");
        config.put("success_criteria", "Test passes");
        config.put("max_iterations", 2);
        config.put("iteration_timeout_seconds", 300);
        config.put("require_approval", false);
        config.put("max_repeated_actions", 10); // disable repeated action detection for this test
        BlockDefinition block = BlockDefinition.builder().type("agentic").config(config).build();

        StepResult result = executor.execute(block, buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);

        Map<String, Object> metadata = result.getMetadata();
        assertThat(metadata.get("goalAchieved")).isEqualTo(false);
        assertThat(metadata.get("totalIterations")).isEqualTo(2);
    }

    @Test
    void execute_cancellationStopsLoop() throws Exception {
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");

        when(commandExecutor.executeCommandOnHost("/test/project", "Do something", null, null, null))
                .thenReturn(new CliSession.EnrichedCommandResult("partial", 0, null));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        ExecutionContext context = buildContext();
        // Cancel after first iteration observe
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull()))
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
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull())).thenThrow(new java.io.IOException("Connection refused"));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Test"), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("REASON ERROR");
    }

    @Test
    void execute_containerCommandFailure_isObservedAndRetried() throws Exception {
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");

        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: First try\nACTION: Run tests\nSUMMARY: Running tests"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Try different approach\nACTION: Fix and run\nSUMMARY: Fixing"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Fixed\nACTION:\nSUMMARY: All good"));

        when(commandExecutor.executeCommandOnHost("/test/project", "Run tests", null, null, null))
                .thenReturn(new CliSession.EnrichedCommandResult("Tests failed: 3 errors", 1, null));
        when(commandExecutor.executeCommandOnHost("/test/project", "Fix and run", null, null, null))
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
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull())).thenReturn(
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
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull())).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        executor.execute(buildBlock("Test"), buildContext());

        // Verify buildConversationPrompt was called with a system prompt containing learning data
        verify(hostClaude).buildConversationPrompt(contains("Previously Tried Strategies"), anyList());
    }

    @Test
    void execute_learningServiceCalledWithWorkflowId_notRunId() throws Exception {
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull())).thenReturn(
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
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull())).thenReturn(
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
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull())).thenReturn(
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
    void execute_tokenBudgetExceeded_stopsLoop() throws Exception {
        // Create a response with high token usage to exceed a small budget
        ClaudeJsonResponse.Usage highUsage = new ClaudeJsonResponse.Usage(60000, 60000);
        ClaudeJsonResponse parsedWithUsage = new ClaudeJsonResponse(
                "result", null, 1.0, 1000L, 800L, 1, highUsage, null, null);

        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(new ReasoningResult(
                        "GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do work\nSUMMARY: Step", parsedWithUsage));

        when(commandExecutor.executeCommandOnHost("/test/project", "Do work", null, null, null))
                .thenReturn(new CliSession.EnrichedCommandResult("Done", 0, parsedWithUsage));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        // Set a very low token budget (100k) that will be exceeded by first iteration (240k tokens)
        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Budget test");
        config.put("success_criteria", "Test passes");
        config.put("max_iterations", 10);
        config.put("iteration_timeout_seconds", 300);
        config.put("max_total_tokens", 100000);
        config.put("max_repeated_actions", 10);
        config.put("require_approval", false);
        BlockDefinition block = BlockDefinition.builder().type("agentic").config(config).build();

        StepResult result = executor.execute(block, buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("TOKEN BUDGET EXCEEDED");
        assertThat(result.getMetadata().get("stopReason")).isEqualTo("TOKEN_BUDGET_EXCEEDED");
        assertThat(((Number) result.getMetadata().get("totalIterations")).intValue()).isEqualTo(1);
    }

    @Test
    void execute_tokenTrackingFailed_afterConsecutiveBlindIterations() throws Exception {
        // All iterations return null parsed (no usage data) — triggers fallback estimation + abort after 3
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Step A\nSUMMARY: Doing A"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Continuing\nACTION: Step B\nSUMMARY: Doing B"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: More work\nACTION: Step C\nSUMMARY: Doing C"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Keep going\nACTION: Step D\nSUMMARY: Doing D"));

        // EnrichedCommandResult with null parsed → no usage data
        when(commandExecutor.executeCommandOnHost(eq("/test/project"), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(new CliSession.EnrichedCommandResult("output", 0, null));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Blind budget test");
        config.put("success_criteria", "Test passes");
        config.put("max_iterations", 10);
        config.put("iteration_timeout_seconds", 300);
        config.put("max_total_tokens", 200000);
        config.put("max_repeated_actions", 10);
        config.put("require_approval", false);
        BlockDefinition block = BlockDefinition.builder().type("agentic").config(config).build();

        StepResult result = executor.execute(block, buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("TOKEN TRACKING FAILED");
        assertThat(result.getMetadata().get("stopReason")).isEqualTo("TOKEN_TRACKING_FAILED");
        assertThat(result.getMetadata().get("tokenEstimationFallback")).isEqualTo(true);
        // Should stop after 3 consecutive blind iterations
        assertThat(((Number) result.getMetadata().get("totalIterations")).intValue()).isEqualTo(3);
        // Fallback estimation should have produced non-zero token count
        assertThat(((Number) result.getMetadata().get("totalTokensUsed")).intValue()).isGreaterThan(0);
    }

    @Test
    void execute_fallbackEstimation_resetsOnRealUsage() throws Exception {
        // First iteration: no usage (blind), second: real usage, third: no usage again — should NOT abort
        ClaudeJsonResponse.Usage realUsage = new ClaudeJsonResponse.Usage(1000, 500);
        ClaudeJsonResponse parsedWithUsage = new ClaudeJsonResponse(
                "result", null, 0.01, 500L, 400L, 1, realUsage, null, null);

        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: First\nACTION: Step 1\nSUMMARY: S1"))
                .thenReturn(new ReasoningResult(
                        "GOAL_STATUS: IN_PROGRESS\nRATIONALE: Second\nACTION: Step 2\nSUMMARY: S2", parsedWithUsage))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Third\nACTION: Step 3\nSUMMARY: S3"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        when(commandExecutor.executeCommandOnHost(eq("/test/project"), eq("Step 1"), isNull(), isNull(), isNull()))
                .thenReturn(new CliSession.EnrichedCommandResult("out1", 0, null));
        when(commandExecutor.executeCommandOnHost(eq("/test/project"), eq("Step 2"), isNull(), isNull(), isNull()))
                .thenReturn(new CliSession.EnrichedCommandResult("out2", 0, parsedWithUsage));
        when(commandExecutor.executeCommandOnHost(eq("/test/project"), eq("Step 3"), isNull(), isNull(), isNull()))
                .thenReturn(new CliSession.EnrichedCommandResult("out3", 0, null));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Reset test", 10), buildContext());

        // Goal achieved because consecutive blind count reset at iteration 2
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata().get("goalAchieved")).isEqualTo(true);
    }

    @Test
    void execute_setsContextVariables() throws Exception {
        when(hostClaude.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull())).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        ExecutionContext context = buildContext();
        executor.execute(buildBlock("Test"), context);

        assertThat(context.getVariable("agentic_goal_achieved")).isEqualTo(true);
        assertThat(context.getVariable("agentic_iterations")).isEqualTo(1);
    }
}
