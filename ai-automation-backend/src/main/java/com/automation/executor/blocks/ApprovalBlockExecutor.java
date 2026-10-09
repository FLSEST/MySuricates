package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ApprovalRejection;
import com.automation.executor.ApprovalRequest;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.service.workflow.ExecutionBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@RequiredArgsConstructor
@Slf4j
public class ApprovalBlockExecutor implements BlockExecutor {

    private final ExecutionBroadcaster broadcaster;

    @Override
    public Set<String> supportedTypes() {
        return Set.of("approval");
    }

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        int stepIndex = context.nextStepIndex();
        Instant start = Instant.now();
        Map<String, Object> config = block.getConfig();

        // Resolved, so that whoever approves sees the basis instead of {{ block.X.output }} (TODO e2efb393)
        String question = context.resolveTemplate(
                (String) config.getOrDefault("question", "Approval required"));
        List<String> options = config.containsKey("options")
                ? (List<String>) config.get("options")
                : List.of("Yes", "Yes always", "No");
        List<String> rejectAnswers = ApprovalRejection.rejectAnswers(config.get("reject_options"));
        List<String> rejectOptions = ApprovalRejection.rejectingOptions(options, rejectAnswers);
        String mode = "auto".equals(resolveSwitch(config.get("mode"), context)) ? "auto" : "ask";
        String autoAnswer = resolveSwitch(config.get("auto_answer"), context);
        String onNo = (String) config.getOrDefault("on_no", "continue");
        String variableName = (String) config.getOrDefault("variable_name", "approval_answer");
        int timeoutMinutes = config.containsKey("timeout_minutes")
                ? ((Number) config.get("timeout_minutes")).intValue()
                : 30;

        String answer = presetAnswer(mode, autoAnswer, question, context);
        if (answer == null) {
            ApprovalRequest request = ApprovalRequest.builder()
                    .blockId("approval-" + stepIndex)
                    .question(question)
                    .options(options)
                    .rejectOptions(rejectOptions)
                    .stepIndex(stepIndex)
                    .timeoutMinutes(timeoutMinutes)
                    .build();
            CompletableFuture<String> pending = ask(request, context);
            try {
                answer = context.awaitApproval(pending, timeoutMinutes, TimeUnit.MINUTES);
            } catch (TimeoutException e) {
                log.warn("Approval timed out for question: {}", question);
                return buildResult(stepIndex, start, question, "Timed out waiting for approval",
                        StepStatus.FAILED);
            } catch (Exception e) {
                log.warn("Approval interrupted for question: {}", question);
                return buildResult(stepIndex, start, question, "Approval cancelled: " + e.getMessage(),
                        StepStatus.FAILED);
            }
            Map<String, Object> resolved = new HashMap<>();
            resolved.put("stepIndex", stepIndex);
            resolved.put("answer", answer);
            broadcaster.broadcastLogMessage(context.getWorkflowRunId(), "approval_resolved", resolved);
        }

        // Handle "Yes always" — remember for future steps in this run
        if ("Yes always".equalsIgnoreCase(answer)) {
            context.rememberApproval(question, answer);
        }

        // Store answer as context variable
        context.setVariable(variableName, answer);

        // Determine step status based on answer and on_no config
        if (ApprovalRejection.isRejection(answer, rejectAnswers)) {
            return switch (onNo) {
                case "fail" -> buildResult(stepIndex, start, question,
                        "Approval denied: " + answer, StepStatus.FAILED);
                case "skip" -> buildResult(stepIndex, start, question,
                        "Approval denied, skipping: " + answer, StepStatus.SKIPPED);
                default -> buildResult(stepIndex, start, question,
                        "Approval denied (continuing): " + answer, StepStatus.COMPLETED);
            };
        }

        return buildResult(stepIndex, start, question, "Approved: " + answer, StepStatus.COMPLETED);
    }

    /**
     * The answer without asking anyone: {@code auto_answer} in auto mode, else an answer remembered from
     * "Yes always" in this run; {@code null} when the question has to be asked.
     */
    private static String presetAnswer(String mode, String autoAnswer, String question, ExecutionContext context) {
        if ("auto".equals(mode) && autoAnswer != null) {
            log.info("Approval auto-answered '{}' for question: {}", autoAnswer, question);
            return autoAnswer;
        }
        String remembered = context.getRememberedApproval(question);
        if (remembered != null) {
            log.info("Approval remembered '{}' for question: {}", remembered, question);
        }
        return remembered;
    }

    /** Opens the question in the run and announces it on the run's log topic; the future carries the answer. */
    private CompletableFuture<String> ask(ApprovalRequest request, ExecutionContext context) {
        CompletableFuture<String> pending = context.requestApproval(request);
        Map<String, Object> payload = new HashMap<>();
        payload.put("blockId", request.getBlockId());
        payload.put("question", request.getQuestion());
        payload.put("options", request.getOptions());
        payload.put("rejectOptions", request.getRejectOptions());
        payload.put("stepIndex", request.getStepIndex());
        broadcaster.broadcastLogMessage(context.getWorkflowRunId(), "approval_required", payload);
        return pending;
    }

    private StepResult buildResult(int stepIndex, Instant start, String input, String output, StepStatus status) {
        Instant end = Instant.now();
        return StepResult.builder()
                .stepIndex(stepIndex)
                .input("[Approval] " + input)
                .output(output)
                .status(status)
                .startedAt(start)
                .completedAt(end)
                .durationMs(end.toEpochMilli() - start.toEpochMilli())
                .build();
    }

    /**
     * {@code mode} and {@code auto_answer} may be set via variable (e.g.
     * {@code {{ approval_mode }}} from the defaultContext), so that a workflow switches without manual edit
     * between interactive and unattended (TODO 2a05c365). Exception: if the run carries
     * foreign input, a placeholder stays unresolved - otherwise a webhook payload could grant the
     * approval itself. Then "ask" or no automatic answer applies.
     */
    private static String resolveSwitch(Object raw, ExecutionContext context) {
        if (!(raw instanceof String value)) return null;
        if (value.contains("{{")) {
            if (context.hasUntrustedInput()) return null;
            value = context.resolveTemplate(value);
            if (value.contains("{{")) return null; // Variable missing - never take a placeholder as the answer
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }
}
