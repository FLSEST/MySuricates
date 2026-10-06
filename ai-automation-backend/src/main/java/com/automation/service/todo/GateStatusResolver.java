package com.automation.service.todo;

import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
import com.automation.event.WorkflowRunCompletedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
@Slf4j
public class GateStatusResolver {

    public record GateResult(GateStatus status, String category, String description) {}

    /**
     * Resolves a GateResult from the event's run metadata or derives it from the run status.
     * Returns null for CANCELLED/PAUSED runs (no todo action needed).
     */
    public GateResult resolve(WorkflowRunCompletedEvent event) {
        Map<String, Object> meta = event.getRunMetadata();

        // 1. Explicit gate_status in run metadata
        Object explicitGate = meta.get("gate_status");
        if (explicitGate != null) {
            try {
                GateStatus status = GateStatus.valueOf(explicitGate.toString().toUpperCase(Locale.ROOT));
                String category = meta.containsKey("gate_category")
                        ? String.valueOf(meta.get("gate_category"))
                        : deriveCategory(status);
                String description = meta.containsKey("gate_description")
                        ? String.valueOf(meta.get("gate_description"))
                        : null;
                return new GateResult(status, category, description);
            } catch (IllegalArgumentException invalid) {
                // An unreadable value is a finding, not nothing (TODO 63ecc124): before, it fell
                // silently through to the derivation and became GREEN on COMPLETED. Now it stays
                // visible. A verdict that is stricter anyway from the run status (FAILED -> RED) keeps
                // precedence, though - the unreadable value may only tighten a verdict, never soften it.
                log.warn("Run {} reported an unreadable gate_status '{}'", event.getRunId(), explicitGate);
                GateResult derived = deriveFromRunStatus(event);
                if (derived != null && derived.status() == GateStatus.GREEN) {
                    return new GateResult(GateStatus.YELLOW, "gate-invalid",
                            "Unlesbares Gate-Urteil: " + explicitGate);
                }
                return derived;
            }
        }

        // 2. Derive from RunStatus
        return deriveFromRunStatus(event);
    }

    private GateResult deriveFromRunStatus(WorkflowRunCompletedEvent event) {
        return switch (event.getStatus()) {
            case FAILED -> new GateResult(GateStatus.RED, "execution-failure",
                    extractErrorMessage(event.getError()));
            case COMPLETED -> new GateResult(GateStatus.GREEN, "success", null);
            case COMPLETED_WITH_ERRORS -> new GateResult(GateStatus.YELLOW, "partial-success", null);
            case CANCELLED, PAUSED, PENDING, RUNNING, INTERRUPTED -> null;
        };
    }

    private String deriveCategory(GateStatus status) {
        return switch (status) {
            case RED -> "quality-gate-failure";
            case YELLOW -> "quality-gate-warning";
            case GREEN -> "success";
        };
    }

    private String extractErrorMessage(Map<String, Object> error) {
        if (error == null || error.isEmpty()) return null;
        Object msg = error.get("message");
        return msg != null ? msg.toString() : null;
    }
}
