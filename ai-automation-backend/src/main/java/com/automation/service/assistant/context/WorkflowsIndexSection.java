package com.automation.service.assistant.context;

import com.automation.dto.AssistantChatRequest;
import com.automation.entity.Workflow;
import com.automation.repository.WorkflowRepository;
import com.automation.service.assistant.AssistantFormatUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lists the user's workflows (max 25), prioritizing the project the user is
 * currently focused on (page context project or the active workflow's project).
 *
 * <p>Not an {@link AssistantContextSection}: it renders from the workflow list the
 * builder loads once and shares across sections, and needs the page context for
 * focus resolution, so the standard two-argument signature does not fit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowsIndexSection {

    private final WorkflowRepository workflowRepository;

    /**
     * Appends the workflows section.
     *
     * @param sb           the prompt being built
     * @param compact      true if this section should be rendered in compact mode
     * @param allWorkflows pre-loaded workflow list shared across sections
     * @param ctx          page context used to prioritize the focused project (nullable)
     */
    public void append(StringBuilder sb, boolean compact, List<Workflow> allWorkflows,
                       AssistantChatRequest.PageContext ctx) {
        if (allWorkflows.isEmpty()) return;
        try {
            if (compact) {
                long projectCount = allWorkflows.stream()
                        .map(w -> w.getProject() != null ? w.getProject().getId() : "")
                        .distinct().count();
                sb.append("**Workflows:** ").append(allWorkflows.size()).append(" total (")
                        .append(projectCount).append(" projects)\n");
                return;
            }

            String focusProjectId = resolveFocusProjectId(ctx);
            List<Workflow> toShow;
            if (focusProjectId != null) {
                toShow = allWorkflows.stream()
                        .filter(w -> w.getProject() != null && focusProjectId.equals(w.getProject().getId()))
                        .sorted(Comparator.comparing(Workflow::getName))
                        .collect(Collectors.toList());
                List<Workflow> others = allWorkflows.stream()
                        .filter(w -> w.getProject() == null || !focusProjectId.equals(w.getProject().getId()))
                        .sorted(Comparator.comparing(Workflow::getName))
                        .limit(Math.max(0, 25 - toShow.size()))
                        .collect(Collectors.toList());
                toShow = new ArrayList<>(toShow);
                toShow.addAll(others);
            } else {
                toShow = allWorkflows.stream()
                        .sorted(Comparator.comparing(Workflow::getName))
                        .limit(25)
                        .collect(Collectors.toList());
            }

            sb.append("**My Workflows (").append(allWorkflows.size()).append("):**\n");
            toShow.forEach(w -> {
                String projectName = w.getProject() != null ? w.getProject().getName() : "—";
                String projectId = w.getProject() != null ? w.getProject().getId() : "—";
                sb.append("  - [").append(w.getName()).append("] ")
                        .append(projectName).append(" (project: ").append(projectId).append(")")
                        .append(" — /workflows/").append(w.getId());
                if (w.getDescription() != null && !w.getDescription().isBlank()) {
                    sb.append(" (").append(AssistantFormatUtils.truncate(w.getDescription().trim(), 60)).append(")");
                }
                int inputCount = w.getDefaultContext() != null ? w.getDefaultContext().size() : 0;
                if (inputCount > 0) {
                    sb.append(" — ").append(inputCount).append(" inputs");
                }
                sb.append("\n");
            });
            if (allWorkflows.size() > 25) {
                sb.append("  … and ").append(allWorkflows.size() - 25)
                  .append(" more. A workflow's inputs via <fetch type=\"workflow-inputs\" id=\"<workflow-id>\"/>\n");
            } else if (toShow.stream().anyMatch(w -> w.getDefaultContext() != null && !w.getDefaultContext().isEmpty())) {
                sb.append("  A workflow's inputs via <fetch type=\"workflow-inputs\" id=\"<workflow-id>\"/>\n");
            }
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Could not build workflow list for assistant context", e);
        }
    }

    private String resolveFocusProjectId(AssistantChatRequest.PageContext ctx) {
        if (ctx == null) return null;
        if (ctx.projectId() != null) return ctx.projectId();
        if (ctx.workflowId() == null) return null;
        try {
            return workflowRepository.findByIdWithSkills(ctx.workflowId())
                    .map(Workflow::getProject)
                    .map(project -> project != null ? project.getId() : null)
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }
}
