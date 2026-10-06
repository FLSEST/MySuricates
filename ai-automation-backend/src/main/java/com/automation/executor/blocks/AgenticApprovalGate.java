package com.automation.executor.blocks;

import com.automation.executor.ApprovalRequest;
import com.automation.executor.ExecutionContext;
import com.automation.service.ai.ConversationPrompt.Turn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.automation.config.ExecutionDefaults.TRUNCATE_MEDIUM;
import static com.automation.config.ExecutionDefaults.TRUNCATE_SHORT;
import static com.automation.executor.blocks.AgenticPromptBuilder.truncate;

/** The optional approval gate of the agentic loop: asks the user whether to proceed with the planned action. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgenticApprovalGate {

    /** Outcome of an approval request. */
    public enum Decision {
        /** Approved — proceed to the ACT phase. */
        PROCEED,
        /** Rejected — skip the ACT phase and go straight to the next REASON iteration. */
        RETRY_ITERATION,
        /** Timed out, was interrupted, or errored — abort the loop. */
        STOP,
        /** "Yes always" — proceed, and disable the gate for the remaining iterations. */
        PROCEED_AND_DISABLE
    }

    private final AgenticEventBroadcaster events;

    public Decision ask(ExecutionContext context, int stepIndex, int iteration, AgenticPlan plan,
                        Map<String, Object> iterData, StringBuilder fullOutput, List<Turn> history) {
        // Erst die Frage hinterlegen, dann melden: Die Laufseite fragt auf dieses Ereignis hin
        // GET /executions/{id}/approval ab (TODO 5b08cf33) und muss die Frage dort schon finden.
        CompletableFuture<String> future = context.requestApproval(
                ApprovalRequest.builder()
                        .blockId("agentic-" + stepIndex + "-iter-" + iteration)
                        .question("Approve action: " + truncate(plan.getAction(), TRUNCATE_SHORT))
                        .options(List.of("Yes", "Yes always", "No"))
                        .rejectOptions(List.of("No"))
                        .stepIndex(stepIndex)
                        .timeoutMinutes(30)
                        .build());

        events.broadcast(context.getWorkflowRunId(), stepIndex,
                "agentic_approval_required",
                Map.of("iteration", iteration,
                        "action", plan.getAction(),
                        "rationale", truncate(plan.getRationale(), TRUNCATE_MEDIUM)));

        String answer;
        try {
            answer = context.awaitApproval(future, 30, TimeUnit.MINUTES);
        } catch (java.util.concurrent.TimeoutException e) {
            iterData.put("result", "approval_timeout");
            fullOutput.append("[iteration ").append(iteration)
                    .append(" - APPROVAL TIMEOUT]\n");
            return Decision.STOP;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            iterData.put("result", "approval_interrupted");
            fullOutput.append("[iteration ").append(iteration)
                    .append(" - INTERRUPTED]\n");
            return Decision.STOP;
        } catch (Exception e) {
            log.warn("Approval gate failed at iteration {}: {}", iteration, e.getMessage());
            iterData.put("result", "approval_error");
            fullOutput.append("[iteration ").append(iteration)
                    .append(" - APPROVAL ERROR: ").append(e.getMessage()).append("]\n");
            return Decision.STOP;
        }

        if ("No".equalsIgnoreCase(answer)) {
            // The rejection must reach the shared conversation, otherwise the next
            // REASON call re-proposes the same action (TODO 67512633).
            history.add(new Turn("user",
                    "The user rejected this action. Propose an alternative approach."));
            iterData.put("approval", "rejected");
            fullOutput.append("[iteration ").append(iteration)
                    .append(" - REJECTED]: ").append(plan.getAction()).append("\n");
            return Decision.RETRY_ITERATION;
        }
        if ("Yes always".equalsIgnoreCase(answer)) {
            iterData.put("approval", "approved");
            return Decision.PROCEED_AND_DISABLE;
        }
        iterData.put("approval", "approved");
        return Decision.PROCEED;
    }
}
