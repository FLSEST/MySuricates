package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ApprovalRejection;
import com.automation.executor.ApprovalRequest;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.executor.blocks.StepActors.Actor;
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
    private final StepActors actors;

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

        // Who answers (TODO dca0c03e): a person as always, an AI call in the role, or both (shadow)
        Actor actor = StepActors.resolve(config, context);
        String refusal = actors.refusal(actor, "approval");
        if (refusal != null) {
            return buildResult(stepIndex, start, question, refusal, StepStatus.FAILED, null);
        }

        String preset = presetAnswer(mode, autoAnswer, question, context);
        Outcome outcome = preset != null
                ? new Outcome(preset, null, null, false)
                : obtain(block, context, stepIndex, actor, ApprovalRequest.builder()
                        .blockId("approval-" + stepIndex)
                        .question(question)
                        .options(options)
                        .rejectOptions(rejectOptions)
                        .stepIndex(stepIndex)
                        .timeoutMinutes(timeoutMinutes)
                        .build());
        if (outcome.failure() != null) {
            return buildResult(stepIndex, start, question, outcome.failure(), StepStatus.FAILED, outcome.metadata());
        }
        String answer = outcome.answer();

        // Handle "Yes always" — remember for future steps in this run. Only a person's answer counts: an AI
        // actor's "Yes always" would otherwise answer a later approval cast as human without asking anyone.
        if ("Yes always".equalsIgnoreCase(answer) && !outcome.byAi()) {
            context.rememberApproval(question, answer);
        }
        context.setVariable(variableName, answer);
        return finish(stepIndex, start, question, answer, rejectAnswers, onNo, outcome.metadata());
    }

    /**
     * The answer of a step nobody preset, or why there is none.
     *
     * @param failure the step output when the step fails before an answer (AI actor, timeout, cancel)
     * @param byAi    the AI actor answered — its "Yes always" is not remembered
     */
    private record Outcome(String answer, String failure, Map<String, Object> metadata, boolean byAi) {}

    /** The AI actor answers, or a person does (in shadow mode with the AI's proposal below the question). */
    private Outcome obtain(BlockDefinition block, ExecutionContext context, int stepIndex, Actor actor,
                           ApprovalRequest request) {
        String question = request.getQuestion();
        List<String> options = request.getOptions();
        ActorDecision decision = actors.decide(actor, block, context, stepIndex, approvalTask(question, options),
                text -> offeredOption(request, text));
        if (decision.failed()) {
            return new Outcome(null, decision.failure(), decision.aiMetadata(), false);
        }
        if (decision.answeredByAi()) {
            log.info("Approval answered '{}' by the AI actor for question: {}", decision.answer(), question);
            return new Outcome(decision.answer(), null, decision.aiMetadata(), true);
        }
        return askPerson(request.toBuilder().question(decision.shown(question)).build(), question, context, decision);
    }

    /** Asks the person and waits; timeout and cancel fail the step but keep a shadow step's AI call. */
    private Outcome askPerson(ApprovalRequest request, String question, ExecutionContext context,
                              ActorDecision decision) {
        CompletableFuture<String> pending = ask(request, context);
        String answer;
        try {
            answer = context.awaitApproval(pending, request.getTimeoutMinutes(), TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.warn("Approval timed out for question: {}", question);
            return new Outcome(null, "Timed out waiting for approval", decision.unanswered(), false);
        } catch (Exception e) {
            log.warn("Approval interrupted for question: {}", question);
            return new Outcome(null, "Approval cancelled: " + e.getMessage(), decision.unanswered(), false);
        }
        Map<String, Object> resolved = new HashMap<>();
        resolved.put("stepIndex", request.getStepIndex());
        resolved.put("answer", answer);
        broadcaster.broadcastLogMessage(context.getWorkflowRunId(), "approval_resolved", resolved);
        return new Outcome(answer, null, decision.afterPerson(sameOption(answer, decision.proposalText())), false);
    }

    /** The AI actor's answer as one of the offered options (their spelling), or why it is none of them. */
    private static String offeredOption(ApprovalRequest request, String text) {
        try {
            return request.acceptedAnswer(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("none of the options " + request.getOptions(), e);
        }
    }

    /** The step result for an answer: a rejection follows {@code on_no}, anything else is approved. */
    private StepResult finish(int stepIndex, Instant start, String question, String answer, List<String> rejectAnswers,
                              String onNo, Map<String, Object> metadata) {
        if (ApprovalRejection.isRejection(answer, rejectAnswers)) {
            return switch (onNo) {
                case "fail" -> buildResult(stepIndex, start, question,
                        "Approval denied: " + answer, StepStatus.FAILED, metadata);
                case "skip" -> buildResult(stepIndex, start, question,
                        "Approval denied, skipping: " + answer, StepStatus.SKIPPED, metadata);
                default -> buildResult(stepIndex, start, question,
                        "Approval denied (continuing): " + answer, StepStatus.COMPLETED, metadata);
            };
        }
        return buildResult(stepIndex, start, question, "Approved: " + answer, StepStatus.COMPLETED, metadata);
    }

    /** The task of the AI actor: the resolved question and the options it may pick from. */
    static String approvalTask(String question, List<String> options) {
        StringBuilder task = new StringBuilder("## Question\n\n").append(question).append("\n\n## Allowed answers\n\n");
        options.forEach(option -> task.append("- ").append(option).append('\n'));
        return task.append("\nReply with exactly one of the allowed answers, spelled as listed, and nothing else.")
                .toString();
    }

    /** Options compare case-insensitively, like {@link ApprovalRequest#acceptedAnswer}. */
    private static boolean sameOption(String answer, String proposal) {
        return answer != null && proposal != null && answer.strip().equalsIgnoreCase(proposal.strip());
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

    private StepResult buildResult(int stepIndex, Instant start, String input, String output, StepStatus status,
                                   Map<String, Object> metadata) {
        Instant end = Instant.now();
        return StepResult.builder()
                .stepIndex(stepIndex)
                .input("[Approval] " + input)
                .output(output)
                .status(status)
                .startedAt(start)
                .completedAt(end)
                .durationMs(end.toEpochMilli() - start.toEpochMilli())
                .metadata(metadata == null ? null : new HashMap<>(metadata))
                .build();
    }

    /**
     * {@code mode} and {@code auto_answer} may be set via variable (e.g.
     * {@code {{ approval_mode }}} from the defaultContext), so that a workflow switches without manual edit
     * between interactive and unattended (TODO 2a05c365). Exception: if the run carries
     * foreign input, a placeholder stays unresolved - otherwise a webhook payload could grant the
     * approval itself. Then "ask" or no automatic answer applies. {@code actor} is read the same way
     * ({@link StepActors#resolve}).
     */
    static String resolveSwitch(Object raw, ExecutionContext context) {
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
