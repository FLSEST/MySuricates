package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class ApprovalBlockExecutorTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private ApprovalBlockExecutor executor;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.builder()
                .containerId("container-1")
                .workflowRunId("run-1")
                .build();
    }

    @Test
    void supports_approval_returnsTrue() {
        assertThat(executor.supports("approval")).isTrue();
    }

    @Test
    void supports_other_returnsFalse() {
        assertThat(executor.supports("simple")).isFalse();
        assertThat(executor.supports("shell")).isFalse();
    }

    @Test
    void execute_autoMode_usesAutoAnswer() {
        BlockDefinition block = BlockDefinition.builder()
                .type("approval")
                .config(new HashMap<>(Map.of(
                        "question", "Deploy to production?",
                        "mode", "auto",
                        "auto_answer", "Yes"
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("Approved");
    }

    @Test
    void execute_askMode_rememberedAnswer_usesMemory() {
        context.rememberApproval("Deploy?", "Yes always");

        BlockDefinition block = BlockDefinition.builder()
                .type("approval")
                .config(new HashMap<>(Map.of(
                        "question", "Deploy?",
                        "mode", "ask"
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("Approved");
    }

    @Test
    void execute_askMode_userAnswersYes_returnsCompleted() {
        // When WebSocket broadcast happens, resolve the approval future
        doAnswer(invocation -> {
            context.resolveApproval("Yes");
            return null;
        }).when(messagingTemplate).convertAndSend(
                eq("/topic/executions/run-1/logs"),
                any(Map.class)
        );

        BlockDefinition block = BlockDefinition.builder()
                .type("approval")
                .config(new HashMap<>(Map.of(
                        "question", "Continue?",
                        "mode", "ask"
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("Approved");
    }

    @Test
    void execute_askMode_userAnswersNo_onNoFail_returnsFailed() {
        doAnswer(invocation -> {
            context.resolveApproval("No");
            return null;
        }).when(messagingTemplate).convertAndSend(
                eq("/topic/executions/run-1/logs"),
                any(Map.class)
        );

        BlockDefinition block = BlockDefinition.builder()
                .type("approval")
                .config(new HashMap<>(Map.of(
                        "question", "Continue?",
                        "mode", "ask",
                        "on_no", "fail"
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("denied");
    }

    @Test
    void execute_askMode_userAnswersNo_onNoSkip_returnsSkipped() {
        doAnswer(invocation -> {
            context.resolveApproval("No");
            return null;
        }).when(messagingTemplate).convertAndSend(
                eq("/topic/executions/run-1/logs"),
                any(Map.class)
        );

        BlockDefinition block = BlockDefinition.builder()
                .type("approval")
                .config(new HashMap<>(Map.of(
                        "question", "Continue?",
                        "mode", "ask",
                        "on_no", "skip"
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SKIPPED);
    }

    @Test
    void execute_askMode_userAnswersNo_onNoContinue_returnsCompleted() {
        doAnswer(invocation -> {
            context.resolveApproval("No");
            return null;
        }).when(messagingTemplate).convertAndSend(
                eq("/topic/executions/run-1/logs"),
                any(Map.class)
        );

        BlockDefinition block = BlockDefinition.builder()
                .type("approval")
                .config(new HashMap<>(Map.of(
                        "question", "Continue?",
                        "mode", "ask",
                        "on_no", "continue"
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("continuing");
    }

    @Test
    void execute_yesAlways_remembersForFuture() {
        doAnswer(invocation -> {
            context.resolveApproval("Yes always");
            return null;
        }).when(messagingTemplate).convertAndSend(
                eq("/topic/executions/run-1/logs"),
                any(Map.class)
        );

        BlockDefinition block = BlockDefinition.builder()
                .type("approval")
                .config(new HashMap<>(Map.of(
                        "question", "Approve action?",
                        "mode", "ask"
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(context.getRememberedApproval("Approve action?")).isEqualTo("Yes always");
    }

    @Test
    void execute_autoMode_storesAnswerAsVariable() {
        BlockDefinition block = BlockDefinition.builder()
                .type("approval")
                .config(new HashMap<>(Map.of(
                        "question", "Continue?",
                        "mode", "auto",
                        "auto_answer", "Yes",
                        "variable_name", "user_choice"
                )))
                .build();

        executor.execute(block, context);

        assertThat(context.getVariable("user_choice")).isEqualTo("Yes");
    }
}
