package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.ai.ClaudeAiProvider;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.ai.HostClaudeService.ReasoningResult;
import com.automation.service.ai.ShellScriptCli;
import com.automation.service.cli.claude.ClaudeJsonResponse;
import com.automation.service.cli.CliSession;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.learning.LearningService;
import com.automation.service.system.SystemIdentityService;
import com.automation.service.ai.ConversationPrompt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private LearningService learningService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private SystemIdentityService systemIdentity;

    @Mock
    private AiProviderRegistry aiProviderRegistry;

    private AgenticBlockExecutor executor;

    /** Real provider around the mocked CLI; a spy, so the tests can see the conversation prompt it builds. */
    private ClaudeAiProvider claudeProvider;

    @BeforeEach
    void setUp() {
        lenient().when(systemIdentity.getVersionedName()).thenReturn("MySuricates v0.2.3");

        // Wire up the registry to return a real ClaudeAiProvider backed by the mock hostClaude
        claudeProvider = spy(new ClaudeAiProvider(hostClaude));
        lenient().when(aiProviderRegistry.resolve(any())).thenReturn(claudeProvider);

        AgenticPromptBuilder promptBuilder = new AgenticPromptBuilder(learningService, systemIdentity);
        AgenticEventBroadcaster events = new AgenticEventBroadcaster(messagingTemplate);
        executor = new AgenticBlockExecutor(promptBuilder, events, runRepository, aiProviderRegistry,
                new AgenticApprovalGate(events), new AgenticActionRunner(promptBuilder, events), new AgenticStopRules(events));

        // Default: resolve run-1 -> workflow-1
        Workflow workflow = Workflow.builder().id("workflow-1").build();
        WorkflowRun run = WorkflowRun.builder().id("run-1").workflow(workflow).build();
        lenient().when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
    }

    private ExecutionContext buildContext() {
        ExecutionContext ctx = ExecutionContext.builder()
                .containerId("test-container")
                .workflowRunId("run-1")
                .projectPath("/test/project")
                .variables(new HashMap<>())
                .build();
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
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Already done\nACTION:\nSUMMARY: Goal was already met"));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Test goal"), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("ACHIEVED");

        Map<String, Object> metadata = result.getMetadata();
        assertThat(metadata.get("goalAchieved")).isEqualTo(true);
        assertThat(metadata.get("totalIterations")).isEqualTo(1);

        // Should not have called action execution
        verify(hostClaude, never()).executeAction(anyString(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean());
    }

    @Test
    void execute_multiIterationUntilGoalAchieved() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");

        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Need to create file\nACTION: Create test.txt\nSUMMARY: Creating file"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: File created\nACTION:\nSUMMARY: Done"));

        when(hostClaude.executeAction(eq("Create test.txt"), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("File created", 0, null));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Create a file"), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);

        Map<String, Object> metadata = result.getMetadata();
        assertThat(metadata.get("goalAchieved")).isEqualTo(true);
        assertThat(metadata.get("totalIterations")).isEqualTo(2);
    }

    @Test
    void execute_maxIterationsReached() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(
                rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Still working\nACTION: Try again\nSUMMARY: Retrying"));

        when(hostClaude.executeAction(eq("Try again"), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("Still failing", 1, null));

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
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");

        when(hostClaude.executeAction(eq("Do something"), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("partial", 0, null));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        ExecutionContext context = buildContext();
        // Cancel after first iteration observe
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do something\nSUMMARY: Step 1"))
                .thenAnswer(invocation -> {
                    context.cancel();
                    return rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: More\nACTION: More work\nSUMMARY: Step 2");
                });

        StepResult result = executor.execute(buildBlock("Some goal"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        // Cancelled during the second REASON: its action must not start. The unstubbed ACT call
        // used to return null and fail the step with an NPE, which made this test pass by accident.
        verify(hostClaude, never()).executeAction(eq("More work"), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean());
    }

    @Test
    void execute_hostClaudeError_stopsWithFailure() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull())).thenThrow(new java.io.IOException("Connection refused"));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Test"), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("REASON ERROR");
    }

    /**
     * The CLI itself fails in the REASON phase (limit, login): it prints its message and ends with exit
     * code 1. The block stops after that one call with the message in its output — before 9cc6c615 the
     * message counted as reasoning without an ACTION, and the block reasoned again until max_iterations.
     */
    @Test
    void execute_reasonCliEndsWithNonZeroExitCode_stopsAfterFirstIterationWithItsMessage() throws Exception {
        when(aiProviderRegistry.resolve(any())).thenReturn(
                new ClaudeAiProvider(ShellScriptCli.claudeReasoning("Claude AI usage limit reached", 1)));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Test goal", 3), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("REASON ERROR", "exit code 1", "Claude AI usage limit reached");
        assertThat(result.getMetadata().get("totalIterations")).isEqualTo(1);
    }

    @Test
    void execute_containerCommandFailure_isObservedAndRetried() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");

        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: First try\nACTION: Run tests\nSUMMARY: Running tests"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Try different approach\nACTION: Fix and run\nSUMMARY: Fixing"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Fixed\nACTION:\nSUMMARY: All good"));

        when(hostClaude.executeAction(eq("Run tests"), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("Tests failed: 3 errors", 1, null));
        when(hostClaude.executeAction(eq("Fix and run"), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("All tests pass", 0, null));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Fix tests", 5), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("Exit code: 1");
        assertThat(result.getOutput()).contains("Exit code: 0");
    }

    @Test
    void execute_metadataStoredCorrectly() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(
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

        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        executor.execute(buildBlock("Test"), buildContext());

        // Verify buildConversationPrompt was called with a system prompt containing learning data
        verify(claudeProvider).buildConversationPrompt(contains("Previously Tried Strategies"), anyList());
    }

    @Test
    void execute_learningServiceCalledWithWorkflowId_notRunId() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(learningService.getAgenticStrategyHistory("workflow-1")).thenReturn(List.of());

        executor.execute(buildBlock("Test"), buildContext());

        // Must be called with workflow-1 (the workflow ID), NOT run-1 (the run ID)
        verify(learningService).getAgenticStrategyHistory("workflow-1");
        verify(learningService, never()).getAgenticStrategyHistory("run-1");
    }

    @Test
    void execute_broadcastsWebSocketEvents() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(
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
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(
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

        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new ReasoningResult(
                        "GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do work\nSUMMARY: Step", parsedWithUsage));

        when(hostClaude.executeAction(eq("Do work"), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("Done", 0, parsedWithUsage));

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
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Step A\nSUMMARY: Doing A"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Continuing\nACTION: Step B\nSUMMARY: Doing B"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: More work\nACTION: Step C\nSUMMARY: Doing C"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Keep going\nACTION: Step D\nSUMMARY: Doing D"));

        // ActionResult with null parsed → no usage data
        when(hostClaude.executeAction(anyString(), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("output", 0, null));

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

        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: First\nACTION: Step 1\nSUMMARY: S1"))
                .thenReturn(new ReasoningResult(
                        "GOAL_STATUS: IN_PROGRESS\nRATIONALE: Second\nACTION: Step 2\nSUMMARY: S2", parsedWithUsage))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Third\nACTION: Step 3\nSUMMARY: S3"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        when(hostClaude.executeAction(eq("Step 1"), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("out1", 0, null));
        when(hostClaude.executeAction(eq("Step 2"), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("out2", 0, parsedWithUsage));
        when(hostClaude.executeAction(eq("Step 3"), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("out3", 0, null));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Reset test", 10), buildContext());

        // Goal achieved because consecutive blind count reset at iteration 2
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata().get("goalAchieved")).isEqualTo(true);
    }

    @Test
    void execute_setsContextVariables() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(
                rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        ExecutionContext context = buildContext();
        executor.execute(buildBlock("Test"), context);

        assertThat(context.getVariable("agentic_goal_achieved")).isEqualTo(true);
        assertThat(context.getVariable("agentic_iterations")).isEqualTo(1);
    }

    @Test
    void execute_repeatedActions_abortsLoop() throws Exception {
        ClaudeJsonResponse.Usage usage = new ClaudeJsonResponse.Usage(500, 200);
        ClaudeJsonResponse parsed = new ClaudeJsonResponse("result", null, 0.01, 200L, 100L, 1, usage, null, null);

        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new ReasoningResult("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Trying\nACTION: same action\nSUMMARY: Retry", parsed));

        when(hostClaude.executeAction(eq("same action"), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("failed", 1, parsed));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Repeat test");
        config.put("success_criteria", "Test passes");
        config.put("max_iterations", 10);
        config.put("iteration_timeout_seconds", 300);
        config.put("max_repeated_actions", 3);
        config.put("require_approval", false);
        BlockDefinition block = BlockDefinition.builder().type("agentic").config(config).build();

        StepResult result = executor.execute(block, buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("Same action repeated 3 times");
        assertThat(result.getMetadata().get("stopReason")).isEqualTo("REPEATED_ACTIONS");
    }

    @Test
    void execute_noActionInPlan_asksForAction() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                // First response has no action
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Thinking\nACTION: \nSUMMARY: Planning"))
                // Second response achieves goal
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Test"), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata().get("totalIterations")).isEqualTo(2);
        // No executeAction call for the empty-action iteration
        verify(hostClaude, never()).executeAction(anyString(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean());
    }

    @Test
    void execute_historyCompression_compressesAfterThreshold() throws Exception {
        // Need 8+ turns to trigger compression (keepRecentCount=4, threshold=keepRecentCount+3=7)
        // Each iteration adds 2 turns (assistant reasoning + user observation), plus the initial user turn
        // So 4 iterations = 1 initial + 4*(1 assistant + 1 user) = 9 turns → triggers compression
        ClaudeJsonResponse.Usage usage = new ClaudeJsonResponse.Usage(500, 200);
        ClaudeJsonResponse parsed = new ClaudeJsonResponse("result", null, 0.01, 200L, 100L, 1, usage, null, null);

        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new ReasoningResult("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Step1\nACTION: Action A\nSUMMARY: S1", parsed))
                .thenReturn(new ReasoningResult("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Step2\nACTION: Action B\nSUMMARY: S2", parsed))
                .thenReturn(new ReasoningResult("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Step3\nACTION: Action C\nSUMMARY: S3", parsed))
                .thenReturn(new ReasoningResult("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Step4\nACTION: Action D\nSUMMARY: S4", parsed))
                .thenReturn(new ReasoningResult("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete", parsed));

        when(hostClaude.executeAction(anyString(), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("ok", 0, parsed));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Compression test", 10), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata().get("totalIterations")).isEqualTo(5);

        // Verify buildConversationPrompt was called with history containing compressed turns
        // After compression, history should contain "[Compressed history" in a user turn
        verify(claudeProvider, atLeast(5)).buildConversationPrompt(anyString(), anyList());
    }

    @Test
    void execute_actionCompression_compressesOversizedAction() throws Exception {
        // Generate an action larger than AGENTIC_ACTION_COMPRESS_THRESHOLD (100_000)
        String oversizedAction = "GOAL_STATUS: IN_PROGRESS\nRATIONALE: Big step\nACTION: " +
                "x".repeat(110_000) + "\nSUMMARY: Large action";

        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr(oversizedAction))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        // The compression call via reason (provider.reason)
        when(hostClaude.reasonStructured(contains("OVERSIZED ACTION"), isNull(), isNull(), isNull(), eq(1), eq(60), isNull()))
                .thenReturn(rr("Compressed: do the thing"));

        when(hostClaude.executeAction(anyString(), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("done", 0, null));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Compress test", 5), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        // The compressed action should have been used for execution
        verify(hostClaude).executeAction(eq("Compressed: do the thing"), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false));
    }

    @Test
    void execute_actionCompression_fallsBackToTruncation_onError() throws Exception {
        String oversizedAction = "GOAL_STATUS: IN_PROGRESS\nRATIONALE: Big\nACTION: " +
                "y".repeat(110_000) + "\nSUMMARY: Large";

        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr(oversizedAction))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        // Compression fails
        when(hostClaude.reasonStructured(contains("OVERSIZED ACTION"), isNull(), isNull(), eq(1), eq(60), isNull()))
                .thenThrow(new java.io.IOException("Compression failed"));

        when(hostClaude.executeAction(anyString(), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("done", 0, null));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Truncate test", 5), buildContext());

        // Should still complete — fallback truncation was used
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        // The action passed to executeAction should be truncated (contains [TRUNCATED:...)
        verify(hostClaude).executeAction(contains("[TRUNCATED:"), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false));
    }

    @Test
    void execute_withModelAndInstructions_passedToProvider() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), eq("claude-opus-4-6"), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do it\nSUMMARY: Step"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        when(hostClaude.executeAction(eq("Do it"), eq("claude-opus-4-6"), eq("Custom instructions for this workflow"), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("done", 0, null));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        ExecutionContext context = buildContext();
        context.setModel("claude-opus-4-6");
        context.setInstructionPrefix("Custom instructions for this workflow");

        StepResult result = executor.execute(buildBlock("Model test"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        // System prompt should include custom instructions (called once per iteration)
        verify(claudeProvider, atLeast(1)).buildConversationPrompt(contains("Custom instructions for this workflow"), anyList());
        // Model and instructions passed to executeAction
        verify(hostClaude).executeAction(eq("Do it"), eq("claude-opus-4-6"), eq("Custom instructions for this workflow"), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false));
    }

    @Test
    void execute_workflowModel_usedInReasonPhase() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), eq("claude-opus-4-6"), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        ExecutionContext context = buildContext();
        context.setModel("claude-opus-4-6");

        StepResult result = executor.execute(buildBlock("Reason model test"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        // REASON must receive the workflow model, same as ACT
        verify(hostClaude).reasonStructured(eq("prompt"), anyString(), eq("claude-opus-4-6"), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    void execute_actPhaseException_capturedAsError() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do something\nSUMMARY: Step"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        // ACT phase throws exception
        when(hostClaude.executeAction(eq("Do something"), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenThrow(new RuntimeException("Process crashed"));

        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(buildBlock("Exception test", 5), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("Execution error: Process crashed");
    }

    @Test
    void execute_providerResolved_fromContext() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        ExecutionContext context = buildContext();
        context.setProvider("CODEX");

        executor.execute(buildBlock("Provider test"), context);

        // Provider was resolved from context
        verify(aiProviderRegistry).resolve("CODEX");
    }

    @Test
    void execute_learningServiceThrows_gracefullyHandled() throws Exception {
        when(learningService.getAgenticStrategyHistory(anyString()))
                .thenThrow(new RuntimeException("DB connection failed"));

        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        StepResult result = executor.execute(buildBlock("Resilience test"), buildContext());

        // Should succeed despite learning service failure
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_noWorkflowRun_learningContextEmpty() throws Exception {
        // Run not found in repository
        when(runRepository.findById("run-1")).thenReturn(Optional.empty());

        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));

        StepResult result = executor.execute(buildBlock("No run test"), buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        // Learning service should not be called with null workflow ID
        verify(learningService, never()).getAgenticStrategyHistory(anyString());
    }

    @Test
    void execute_websocketBroadcastFailure_doesNotAbortExecution() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        // WebSocket broadcast throws
        doThrow(new RuntimeException("WebSocket connection lost"))
                .when(messagingTemplate).convertAndSend(anyString(), any(Map.class));

        StepResult result = executor.execute(buildBlock("WS fail test"), buildContext());

        // Should still complete despite broadcast failures
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_perBlockTimeout_propagatedToReasonAndAct() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Timeout propagation test");
        config.put("success_criteria", "Timeout forwarded");
        config.put("max_iterations", 10);
        config.put("iteration_timeout_seconds", 300);
        config.put("require_approval", false);
        config.put("timeout_seconds", 600);
        BlockDefinition block = BlockDefinition.builder().type("agentic").config(config).build();

        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), eq(600), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do it\nSUMMARY: Step"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(hostClaude.executeAction(anyString(), isNull(), isNull(), eq("/test/project"), eq(600), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("done", 0, null));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(block, buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(hostClaude, atLeast(1)).reasonStructured(anyString(), anyString(), isNull(), isNull(), isNull(), eq(600), isNull());
        verify(hostClaude).executeAction(anyString(), isNull(), isNull(), eq("/test/project"), eq(600), eq(false), isNull(), isNull(), isNull(), eq(false));
    }

    @Test
    void execute_reasonAndActPhases_useDistinctSystemPromptArguments() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), eq("claude-opus-4-6"), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do it\nSUMMARY: Step"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Complete"));
        when(hostClaude.executeAction(anyString(), eq("claude-opus-4-6"), eq("WORKFLOW_INSTR_X"), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("done", 0, null));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        ExecutionContext context = buildContext();
        context.setModel("claude-opus-4-6");
        context.setInstructionPrefix("WORKFLOW_INSTR_X");

        executor.execute(buildBlock("Phase-args goal"), context);

        ArgumentCaptor<String> reasonSysPrompt = ArgumentCaptor.forClass(String.class);
        verify(hostClaude, atLeast(1)).reasonStructured(anyString(), reasonSysPrompt.capture(), eq("claude-opus-4-6"), isNull(), isNull(), isNull(), isNull());
        ArgumentCaptor<String> actSysPrompt = ArgumentCaptor.forClass(String.class);
        verify(hostClaude).executeAction(anyString(), eq("claude-opus-4-6"), actSysPrompt.capture(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false));

        // REASON gets the full agentic system prompt (contains goal + structured format)
        assertThat(reasonSysPrompt.getValue()).contains("Phase-args goal");
        assertThat(reasonSysPrompt.getValue()).contains("Your Goal");
        assertThat(reasonSysPrompt.getValue()).contains("GOAL_STATUS:");
        // ACT gets only the raw workflow instruction prefix
        assertThat(actSysPrompt.getValue()).isEqualTo("WORKFLOW_INSTR_X");
        assertThat(actSysPrompt.getValue()).doesNotContain("Your Goal");
    }

    @Test
    void execute_providerExecuteActionThrowsClaudeNotInstalled_marksFailure() throws Exception {
        Map<String, Object> config = new HashMap<>();
        config.put("goal", "Binary missing test");
        config.put("success_criteria", "Claude binary found");
        config.put("max_iterations", 1);
        config.put("iteration_timeout_seconds", 300);
        config.put("require_approval", false);
        config.put("max_repeated_actions", 10);
        BlockDefinition block = BlockDefinition.builder().type("agentic").config(config).build();

        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Working\nACTION: Do something\nSUMMARY: Step"));
        when(hostClaude.executeAction(anyString(), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenThrow(new RuntimeException("claude binary not found in PATH"));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        StepResult result = executor.execute(block, buildContext());

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("claude binary not found in PATH");
    }

    // ── approval gate (characterization, Audit-TODO a253ad3e) ────────

    private static final ConversationPrompt.Turn REJECTION_TURN = new ConversationPrompt.Turn(
            "user", "The user rejected this action. Propose an alternative approach.");

    private BlockDefinition approvalBlock() {
        BlockDefinition block = buildBlock("Guarded goal", 3);
        block.getConfig().put("require_approval", true);
        return block;
    }

    private ExecutionContext contextAnswering(java.util.concurrent.CompletableFuture<String>... answers) {
        ExecutionContext context = spy(buildContext());
        org.mockito.stubbing.Stubber stub = null;
        for (var answer : answers) {
            stub = stub == null ? doReturn(answer) : stub.doReturn(answer);
        }
        stub.when(context).requestApproval(any(com.automation.executor.ApprovalRequest.class));
        return context;
    }

    private void stubOneActionThenAchieved() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Do it\nACTION: rm -rf build\nSUMMARY: Clean"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Done"));
        lenient().when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());
    }

    @Test
    void approval_yes_executesAction_andRecordsApproved() throws Exception {
        stubOneActionThenAchieved();
        when(hostClaude.executeAction(eq("rm -rf build"), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("cleaned", 0, null));
        ExecutionContext context = contextAnswering(java.util.concurrent.CompletableFuture.completedFuture("Yes"));

        StepResult result = executor.execute(approvalBlock(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(context, times(1)).requestApproval(argThat(r -> r.getQuestion().equals("Approve action: rm -rf build")
                && r.getOptions().equals(List.of("Yes", "Yes always", "No")) && r.getBlockId().equals("agentic-0-iter-1")));
        verify(hostClaude).executeAction(eq("rm -rf build"), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false));
        verify(claudeProvider, never()).buildConversationPrompt(anyString(), argThat(h -> h.contains(REJECTION_TURN)));
    }

    @Test
    void approval_yesAlways_disablesGateForFollowingIterations() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: a\nACTION: step one\nSUMMARY: s"))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: b\nACTION: step two\nSUMMARY: s"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Done"));
        when(hostClaude.executeAction(anyString(), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false)))
                .thenReturn(new HostClaudeService.ActionResult("ok", 0, null));
        lenient().when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());
        ExecutionContext context = contextAnswering(java.util.concurrent.CompletableFuture.completedFuture("Yes always"));

        StepResult result = executor.execute(approvalBlock(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(context, times(1)).requestApproval(any(com.automation.executor.ApprovalRequest.class));
        verify(hostClaude, times(2)).executeAction(anyString(), isNull(), isNull(), eq("/test/project"), isNull(), eq(false), isNull(), isNull(), isNull(), eq(false));
    }

    @Test
    void approval_no_skipsAction_recordsRejection_andContinuesLoop() throws Exception {
        stubOneActionThenAchieved();
        ExecutionContext context = contextAnswering(java.util.concurrent.CompletableFuture.completedFuture("No"));

        StepResult result = executor.execute(approvalBlock(), context);

        assertThat(result.getOutput()).contains("[iteration 1 - REJECTED]: rm -rf build");
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata().get("totalIterations")).isEqualTo(2);
        verify(hostClaude, never()).executeAction(anyString(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean());
    }

    @Test
    void approval_no_appendsRejectionTurn_beforeNextReasoning() throws Exception {
        // TODO 67512633: the rejection used to land in a throwaway list and never reached the model.
        // The executor mutates one history instance, so snapshot it per REASON call.
        List<List<ConversationPrompt.Turn>> snapshots = new java.util.ArrayList<>();
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenAnswer(inv -> {
            snapshots.add(List.copyOf(inv.<List<ConversationPrompt.Turn>>getArgument(1)));
            return "prompt";
        });
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: IN_PROGRESS\nRATIONALE: Do it\nACTION: rm -rf build\nSUMMARY: Clean"))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: Done\nACTION:\nSUMMARY: Done"));
        lenient().when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());
        ExecutionContext context = contextAnswering(java.util.concurrent.CompletableFuture.completedFuture("No"));

        StepResult result = executor.execute(approvalBlock(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(0)).doesNotContain(REJECTION_TURN);
        assertThat(snapshots.get(1)).last().isEqualTo(REJECTION_TURN);
        assertThat(snapshots.get(1)).containsOnlyOnce(REJECTION_TURN);
    }

    @Test
    void approval_futureFails_abortsWithApprovalError() throws Exception {
        stubOneActionThenAchieved();
        ExecutionContext context = contextAnswering(
                java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("channel closed")));

        StepResult result = executor.execute(approvalBlock(), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("[iteration 1 - APPROVAL ERROR: ");
        assertThat(result.getOutput()).contains("channel closed");
        verify(hostClaude, never()).executeAction(anyString(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyBoolean());
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_blockLevelProviderAndModel_overrideWorkflowLevel_andAppearInMetadata() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), eq("opus"), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: done\nACTION:\nSUMMARY: ok"));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        ExecutionContext context = buildContext();
        context.setProvider("CODEX");
        context.setModel("gpt-5");
        BlockDefinition block = buildBlock("Test goal");
        block.getConfig().put("provider", "CLAUDE");
        block.getConfig().put("model", "opus");

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(aiProviderRegistry).resolve("CLAUDE");
        Map<String, Object> aiRequest = (Map<String, Object>) result.getMetadata().get("aiRequest");
        assertThat(aiRequest)
                .containsEntry("provider", "CLAUDE")
                .containsEntry("model", "opus")
                .doesNotContainKey("effort");
        assertThat(result.getMetadata()).containsEntry("model", "opus");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_withoutBlockOverride_usesWorkflowProviderAndModel() throws Exception {
        when(claudeProvider.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(hostClaude.reasonStructured(eq("prompt"), anyString(), eq("sonnet"), isNull(), isNull(), isNull(), eq("high")))
                .thenReturn(rr("GOAL_STATUS: ACHIEVED\nRATIONALE: done\nACTION:\nSUMMARY: ok"));
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        ExecutionContext context = buildContext();
        context.setProvider("CLAUDE");
        context.setModel("sonnet");
        context.setEffort("high");

        StepResult result = executor.execute(buildBlock("Test goal"), context);

        verify(aiProviderRegistry).resolve("CLAUDE");
        Map<String, Object> aiRequest = (Map<String, Object>) result.getMetadata().get("aiRequest");
        assertThat(aiRequest)
                .containsEntry("provider", "CLAUDE")
                .containsEntry("model", "sonnet")
                .containsEntry("effort", "high");
    }

    /**
     * TODO 20861200: pi reasons with a --thinking level even without an effort (maxTurns heuristic or its
     * default) — the step names it, beside the effort that was set (here none).
     */
    @Test
    @SuppressWarnings("unchecked")
    void execute_piReasoning_namesItsThinkingLevelInAiRequest() throws Exception {
        com.automation.service.ai.AiProvider pi = mock(com.automation.service.ai.AiProvider.class);
        when(pi.getId()).thenReturn("PI");
        when(pi.getCapabilities()).thenReturn(new com.automation.service.ai.AiProviderCapabilities(
                true, true, true, false, true, false, true, true));
        when(pi.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(pi.reason(any())).thenReturn(new com.automation.service.ai.AiProvider.ReasoningResult(
                "GOAL_STATUS: ACHIEVED\nRATIONALE: done\nACTION:\nSUMMARY: ok",
                com.automation.service.ai.AiResponseMetadata.estimated(10, 5), "medium"));
        when(aiProviderRegistry.resolve(any())).thenReturn(pi);
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        ExecutionContext context = buildContext();
        context.setProvider("PI");

        StepResult result = executor.execute(buildBlock("Test goal"), context);

        Map<String, Object> aiRequest = (Map<String, Object>) result.getMetadata().get("aiRequest");
        assertThat(aiRequest).containsEntry("provider", "PI").containsEntry("reasonThinking", "medium")
                .doesNotContainKey("effort");
    }

    /** Codex has no effort flag: the block does not claim the workflow effort it could not apply (TODO 20adda30). */
    @Test
    @SuppressWarnings("unchecked")
    void execute_providerWithoutEffortSupport_doesNotClaimTheEffort() throws Exception {
        com.automation.service.ai.AiProvider codex = mock(com.automation.service.ai.AiProvider.class);
        when(codex.getId()).thenReturn("CODEX");
        when(codex.getCapabilities()).thenReturn(com.automation.service.ai.AiProviderCapabilities.codex());
        when(codex.buildConversationPrompt(anyString(), anyList())).thenReturn("prompt");
        when(codex.reason(any())).thenReturn(new com.automation.service.ai.AiProvider.ReasoningResult(
                "GOAL_STATUS: ACHIEVED\nRATIONALE: done\nACTION:\nSUMMARY: ok",
                com.automation.service.ai.AiResponseMetadata.estimated(10, 5)));
        when(aiProviderRegistry.resolve(any())).thenReturn(codex);
        when(learningService.getAgenticStrategyHistory(anyString())).thenReturn(List.of());

        ExecutionContext context = buildContext();
        context.setProvider("CODEX");
        context.setEffort("high");

        StepResult result = executor.execute(buildBlock("Test goal"), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        Map<String, Object> aiRequest = (Map<String, Object>) result.getMetadata().get("aiRequest");
        assertThat(aiRequest).containsEntry("provider", "CODEX").doesNotContainKeys("effort", "reasonThinking");
    }
}
