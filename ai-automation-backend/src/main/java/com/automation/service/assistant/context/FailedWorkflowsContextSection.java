package com.automation.service.assistant.context;

import com.automation.dto.response.BrokenWorkflowResponse;
import com.automation.service.execution.WorkflowDebugService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Lists workflows whose most recent run failed — the assistant's view of the Workflow-Debug
 * page (page == "debug"). Uses the SAME {@link WorkflowDebugService} as the REST endpoint, so
 * the assistant and the page never disagree about what is broken.
 *
 * <p>Each entry carries its run id so the assistant can drill into a specific failure via the
 * existing fetch mechanism: {@code <fetch type="run_detail" id="<run-id>"/>}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FailedWorkflowsContextSection implements AssistantContextSection {

    private final WorkflowDebugService workflowDebugService;

    @Override
    public void append(StringBuilder sb, boolean compact) {
        try {
            List<BrokenWorkflowResponse> broken = workflowDebugService.getBrokenWorkflows();
            sb.append("## Kaputte Workflows (letzter Lauf fehlgeschlagen)\n");
            if (broken.isEmpty()) {
                sb.append("Keine — alle Workflows sind beim letzten Lauf sauber durchgelaufen.\n\n");
                return;
            }
            sb.append(broken.size()).append(" Workflow(s) mit rotem letztem Lauf:\n");
            broken.forEach(b -> {
                sb.append("  - [").append(b.workflowName() != null ? b.workflowName() : "Unbekannt").append("] ")
                        .append(b.status());
                if (b.failedStepIndex() != null) {
                    sb.append(", Schritt ").append(b.failedStepIndex() + 1);
                    if (b.failedBlockType() != null) {
                        sb.append(" (").append(b.failedBlockType()).append(")");
                    }
                }
                if (b.errorMessage() != null) {
                    sb.append(" — ").append(truncate(b.errorMessage(), 160));
                }
                sb.append(" (run:").append(b.runId());
                if (b.workflowId() != null) {
                    sb.append(", /workflows/").append(b.workflowId()).append("/run/").append(b.runId());
                }
                sb.append(")\n");
            });
            sb.append("Details zu einem Fehler: <fetch type=\"run_detail\" id=\"<run-id>\"/>\n\n");
        } catch (Exception e) {
            log.debug("Could not load broken workflows for assistant context", e);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
