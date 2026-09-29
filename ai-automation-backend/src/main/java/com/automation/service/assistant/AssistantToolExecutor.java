package com.automation.service.assistant;

import com.automation.config.AssistantGuardrailsConfig;
import com.automation.dto.PendingTool;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.automation.service.assistant.AssistantToolPayloads.sanitizeJson;

/**
 * Parses and executes tool calls embedded in the assistant's response.
 * Tools are encoded as {@code <tool type="...">JSON</tool>} tags.
 *
 * <p><b>Hard-gate (2026-04-19)</b>: tool tags are <b>not</b> executed inline anymore.
 * {@link #extractTools(String)} returns a list of {@link PendingTool}s that the
 * {@link com.automation.controller.AssistantController} hands back to the frontend; execution
 * only happens after the user clicks "Bestätigen" via
 * {@code POST /api/v1/assistant/pending-tools/{id}/confirm}, which invokes
 * {@link #executeConfirmed(PendingTool)}. This prevents the LLM from mutating platform state
 * without explicit user approval, independent of prompt compliance.
 *
 * <p>The per-tool implementations live in {@link AssistantTodoTools},
 * {@link AssistantWorkflowTools} and {@link AssistantChainTools}; the confirmation-card label
 * (names instead of ids) comes from {@link PendingToolDescriber}. This class owns extraction,
 * guardrails (validation + rate limit), dispatch and the audit trail.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AssistantToolExecutor {

    private static final Pattern TOOL_PATTERN =
            Pattern.compile("<tool type=\"([^\"]+)\">(.*?)</tool>", Pattern.DOTALL);
    private static final Set<String> MUTATING_TOOL_TYPES = Set.of(
            "create_todo",
            "update_todo_status",
            "create_workflow",
            "update_workflow",
            "delete_workflow",
            "start_workflow",
            "cancel_run",
            "create_chain"
    );

    private final AssistantTodoTools todoTools;
    private final AssistantWorkflowTools workflowTools;
    private final AssistantChainTools chainTools;
    private final PendingToolRegistry pendingToolRegistry;
    private final AssistantToolValidator toolValidator;
    private final AssistantGuardrailsConfig guardrailsConfig;
    private final ActivityLogService activityLogService;
    private final ModuleRegistry modules;
    private final PendingToolDescriber describer;

    /** Timestamps of confirmed mutating executions within the rolling rate-limit window. */
    private final Deque<Instant> mutationTimestamps = new ArrayDeque<>();

    /**
     * Result of {@link #extractTools(String)}: the cleaned response (with tool tags replaced
     * by placeholder text) plus the list of pending tool proposals to surface to the user.
     */
    public record ExtractionResult(String cleanedResponse, List<PendingTool> pendingTools) {}

    /**
     * Extracts all {@code <tool>} tags from the assistant response, registers them as pending
     * (to be confirmed by the user), and replaces them in the response text with a short
     * "wartet auf Bestätigung" placeholder. No tool is executed at this point.
     */
    public ExtractionResult extractTools(String response) {
        return extractTools(response, type -> true);
    }

    /**
     * Variant of {@link #extractTools(String)} that allows the caller to suppress specific tool
     * types. Suppressed tags are stripped from the response and never registered as pending.
     */
    public ExtractionResult extractTools(String response, Predicate<String> allowToolType) {
        if (response == null) return new ExtractionResult(null, List.of());
        Matcher m = TOOL_PATTERN.matcher(response);
        if (!m.find()) return new ExtractionResult(response, List.of());

        List<PendingTool> pending = new ArrayList<>();
        StringBuffer sb = new StringBuffer();
        do {
            String type = m.group(1);
            if (!allowToolType.test(type)) {
                m.appendReplacement(sb, "");
                continue;
            }
            String payload = sanitizeJson(m.group(2).trim());
            PendingTool tool = pendingToolRegistry.register(type, describer.describe(type, payload), payload);
            pending.add(tool);
            m.appendReplacement(sb,
                    Matcher.quoteReplacement("[Action awaiting confirmation: " + tool.description() + "]"));
        } while (m.find());
        m.appendTail(sb);
        return new ExtractionResult(sb.toString(), pending);
    }

    public boolean isMutatingToolType(String type) {
        return MUTATING_TOOL_TYPES.contains(type);
    }

    /**
     * Executes a tool that the user has just confirmed. Called from the controller after
     * {@link PendingToolRegistry#take(String)} has returned the entry.
     */
    public ToolExecutionResult executeConfirmed(PendingTool tool) {
        return executeConfirmed(tool, null);
    }

    /**
     * Like {@link #executeConfirmed(PendingTool)}, with the UI language of the confirming user ({@code "en"},
     * otherwise German) — the build check after {@code create_workflow}/{@code update_workflow} answers in it.
     */
    public ToolExecutionResult executeConfirmed(PendingTool tool, String language) {
        String payload = sanitizeJson(tool.payloadJson());

        String rawResult = checkGuardrails(tool.type(), payload);
        if (rawResult == null) {
            rawResult = switch (tool.type()) {
                case "create_todo"        -> todoTools.createTodo(payload);
                case "update_todo_status" -> todoTools.updateTodoStatus(payload);
                case "create_workflow"    -> workflowTools.createWorkflow(payload, language);
                case "update_workflow"    -> workflowTools.updateWorkflow(payload, language);
                case "delete_workflow"    -> workflowTools.deleteWorkflow(payload);
                case "start_workflow"     -> workflowTools.startWorkflow(payload);
                case "cancel_run"         -> workflowTools.cancelRun(payload);
                case "create_chain"       -> modules.isEnabled(ModuleCatalog.CHAINS)
                        ? chainTools.createChain(payload)
                        : "⚠ " + ModuleCatalog.CHAINS.disabledMessage("create_chain");
                default -> "[Unknown tool: " + tool.type() + "]";
            };
        }
        boolean success = rawResult != null && rawResult.startsWith("✓");
        audit(tool, success, rawResult);
        return new ToolExecutionResult(tool.id(), tool.type(), success, rawResult);
    }

    /**
     * Pre-execution guardrails (TODO 9f022277): payload validation and a rolling
     * rate limit for mutating actions. Returns a user-facing rejection message,
     * or {@code null} when execution may proceed (recording the rate-limit slot).
     */
    private String checkGuardrails(String type, String payload) {
        var validationError = toolValidator.validate(type, payload);
        if (validationError.isPresent()) {
            return "⚠ Validierung fehlgeschlagen — Aktion nicht ausgeführt: " + validationError.get();
        }
        if (!isMutatingToolType(type)) {
            return null;
        }
        synchronized (mutationTimestamps) {
            Instant windowStart = Instant.now().minus(Duration.ofHours(1));
            while (!mutationTimestamps.isEmpty() && mutationTimestamps.peekFirst().isBefore(windowStart)) {
                mutationTimestamps.pollFirst();
            }
            if (mutationTimestamps.size() >= guardrailsConfig.getMaxActionsPerHour()) {
                return "⚠ Rate-Limit erreicht: maximal " + guardrailsConfig.getMaxActionsPerHour()
                        + " Assistent-Aktionen pro Stunde. Aktion nicht ausgeführt.";
            }
            mutationTimestamps.addLast(Instant.now());
        }
        return null;
    }

    /** Audit trail (TODO 9f022277): every confirmed tool execution lands in the ActivityLog. */
    private void audit(PendingTool tool, boolean success, String result) {
        try {
            String message = "Assistant-Tool " + tool.type() + (success ? " ausgeführt: " : " abgelehnt/fehlgeschlagen: ")
                    + tool.description();
            if (success) {
                activityLogService.info(LogCategory.SYSTEM, message, "AssistantToolExecutor");
            } else {
                activityLogService.warn(LogCategory.SYSTEM, message + " — " + result, "AssistantToolExecutor");
            }
        } catch (Exception e) {
            log.warn("Assistant audit log failed for tool {}: {}", tool.type(), e.getMessage());
        }
    }
}
