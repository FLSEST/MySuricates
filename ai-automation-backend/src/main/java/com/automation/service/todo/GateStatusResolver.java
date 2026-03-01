package com.automation.service.todo;

import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
import com.automation.event.WorkflowRunCompletedEvent;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
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
                GateStatus status = GateStatus.valueOf(explicitGate.toString().toUpperCase());
                String category = meta.containsKey("gate_category")
                        ? String.valueOf(meta.get("gate_category"))
                        : deriveCategory(status);
                String description = meta.containsKey("gate_description")
                        ? String.valueOf(meta.get("gate_description"))
                        : null;
                return new GateResult(status, category, description);
            } catch (IllegalArgumentException ignored) {
                // Invalid gate_status value — fall through to derivation
            }
        }

        // 2. Derive from RunStatus
        return switch (event.getStatus()) {
            case FAILED -> new GateResult(GateStatus.RED, "execution-failure",
                    extractErrorMessage(event.getError()));
            case COMPLETED -> new GateResult(GateStatus.GREEN, "success", null);
            case COMPLETED_WITH_ERRORS -> new GateResult(GateStatus.YELLOW, "partial-success", null);
            case CANCELLED, PAUSED, PENDING, RUNNING -> null;
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
