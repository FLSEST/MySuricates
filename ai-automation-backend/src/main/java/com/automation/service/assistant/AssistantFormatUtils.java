package com.automation.service.assistant;

import com.automation.entity.WorkflowRun;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Shared formatting helpers for assistant prompt building: run links/labels,
 * timestamp formatting, error extraction and inline truncation.
 * Used by {@link AssistantContextBuilder}, {@link AssistantFetchService} and the
 * context sections — single source of truth so all prompt output stays consistent.
 */
public final class AssistantFormatUtils {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());

    private AssistantFormatUtils() {}

    public static String runLink(WorkflowRun run) {
        try {
            if (run.getWorkflow() == null) return "/runs/" + run.getId();
            return "/workflows/" + run.getWorkflow().getId() + "/run/" + run.getId();
        } catch (Exception e) {
            return "/runs/" + run.getId();
        }
    }

    public static String runLabel(WorkflowRun run) {
        String snapshotName = run.getWorkflowName();
        String wfName = snapshotName != null && !snapshotName.isBlank() ? snapshotName : null;
        String proj = "";
        try {
            if (run.getWorkflow() != null) {
                if (wfName == null) wfName = run.getWorkflow().getName();
                if (run.getWorkflow().getProject() != null) {
                    proj = " (" + run.getWorkflow().getProject().getName() + ")";
                }
            } else if (wfName != null) {
                proj = " [deleted]";
            }
        } catch (Exception e) {
            // Lazy-load failure (OSIV disabled and no active session): fall back to snapshot.
            if (wfName != null) proj = " [deleted]";
        }
        return wfName != null ? wfName + proj : "Unknown";
    }

    public static String fmt(Instant instant) {
        return instant != null ? FMT.format(instant) : "—";
    }

    public static String extractError(WorkflowRun run) {
        if (run.getError() == null || run.getError().isEmpty()) return null;
        Object msg = run.getError().get("message");
        return msg != null ? msg.toString() : null;
    }

    public static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s != null ? s : "";
        return s.substring(0, max) + "\n[Output truncated: " + max + " of " + s.length() + " characters]";
    }
}
