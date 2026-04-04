package com.automation.service.assistant.context;

import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.system.SystemIdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class RunsContextSection implements AssistantContextSection {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());

    private final WorkflowRunRepository workflowRunRepository;
    private final SystemIdentityService systemIdentity;

    @Override
    public void append(StringBuilder sb, boolean compact) {
        sb.append("## Mein aktueller Zustand\n");
        sb.append("Version: ").append(systemIdentity.getVersion()).append("\n\n");

        if (compact) {
            try {
                long running = workflowRunRepository.countByStatus(RunStatus.RUNNING);
                List<WorkflowRun> recent = workflowRunRepository.findTop10ByOrderByStartedAtDesc();
                List<WorkflowRun> failed = recent.stream()
                        .filter(r -> r.getStatus() == RunStatus.FAILED).toList();
                String failedIds = failed.stream()
                        .map(r -> r.getId().substring(0, Math.min(8, r.getId().length())))
                        .collect(Collectors.joining(", "));
                sb.append("**Runs:** ").append(recent.size()).append(" zuletzt, ")
                        .append(running).append(" laufend, ").append(failed.size()).append(" FAILED");
                if (!failedIds.isEmpty()) sb.append(" (ids: ").append(failedIds).append(")");
                sb.append("\n\n");
            } catch (Exception e) {
                log.debug("Could not load runs for compact context", e);
            }
            return;
        }

        try {
            List<WorkflowRun> active = workflowRunRepository.findByStatus(RunStatus.RUNNING);
            sb.append("**Aktive Ausführungen:** ").append(active.size()).append("\n");
            active.forEach(r -> sb.append("  - ").append(runLabel(r))
                    .append(" (link:").append(runLink(r)).append(")\n"));
        } catch (Exception e) {
            log.debug("Could not load active runs for assistant context", e);
        }

        sb.append("\n**Letzte 10 Ausführungen:**\n");
        try {
            workflowRunRepository.findTop10ByOrderByStartedAtDesc().forEach(r -> {
                sb.append("  - [").append(fmt(r.getStartedAt())).append("] ")
                        .append(runLabel(r)).append(" — ").append(r.getStatus())
                        .append(" (link:").append(runLink(r)).append(")");
                if (r.getStatus() == RunStatus.FAILED) {
                    String err = extractError(r);
                    if (err != null) sb.append(" — Fehler: ").append(truncate(err, 120));
                }
                sb.append("\n");
            });
        } catch (Exception e) {
            log.debug("Could not load recent runs for assistant context", e);
        }
        sb.append("\n");
    }

    // ── Shared helpers (also used by AssistantContextBuilder for page context) ──

    public String runLink(WorkflowRun run) {
        try {
            if (run.getWorkflow() == null) return "/runs/" + run.getId();
            return "/workflows/" + run.getWorkflow().getId() + "/run/" + run.getId();
        } catch (Exception e) {
            return "/runs/" + run.getId();
        }
    }

    public String runLabel(WorkflowRun run) {
        try {
            String wfName = run.getWorkflow() != null ? run.getWorkflow().getName() : "Unbekannt";
            String proj = run.getWorkflow() != null && run.getWorkflow().getProject() != null
                    ? " (" + run.getWorkflow().getProject().getName() + ")" : "";
            return wfName + proj;
        } catch (Exception e) {
            return "(gelöschter Workflow)";
        }
    }

    public String fmt(Instant instant) {
        return instant != null ? FMT.format(instant) : "—";
    }

    public String extractError(WorkflowRun run) {
        if (run.getError() == null || run.getError().isEmpty()) return null;
        Object msg = run.getError().get("message");
        return msg != null ? msg.toString() : null;
    }

    public String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s != null ? s : "";
        return s.substring(0, max) + "\n[Ausgabe gekürzt: " + max + " von " + s.length() + " Zeichen]";
    }
}
