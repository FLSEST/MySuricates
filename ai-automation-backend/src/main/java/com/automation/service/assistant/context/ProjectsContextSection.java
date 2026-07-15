package com.automation.service.assistant.context;

import com.automation.entity.Workflow;
import com.automation.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lists the user's active (non-archived) projects with their workflow counts.
 *
 * <p>Not an {@link AssistantContextSection}: it renders from the workflow list the
 * builder loads once and shares across sections, so the standard two-argument
 * signature does not fit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectsContextSection {

    private final ProjectRepository projectRepository;

    /**
     * Appends the projects section.
     *
     * @param sb           the prompt being built
     * @param compact      true if this section should be rendered in compact mode
     * @param allWorkflows pre-loaded workflow list shared across sections
     */
    public void append(StringBuilder sb, boolean compact, List<Workflow> allWorkflows) {
        try {
            var projects = projectRepository.findByArchivedAtIsNull();
            if (compact) {
                if (!projects.isEmpty()) {
                    sb.append("**Projects:** ").append(projects.size()).append("\n");
                }
                return;
            }
            if (!projects.isEmpty()) {
                Map<String, Long> wfCountByProject = allWorkflows.stream()
                        .filter(w -> w.getProject() != null)
                        .collect(Collectors.groupingBy(w -> w.getProject().getId(), Collectors.counting()));
                sb.append("**My Projects (").append(projects.size()).append("):**\n");
                projects.forEach(p -> sb.append("  - ").append(p.getName())
                        .append(" (ID: ").append(p.getId()).append(", ")
                        .append(wfCountByProject.getOrDefault(p.getId(), 0L)).append(" workflows)\n"));
                sb.append("\n");
            }
        } catch (Exception e) {
            log.debug("Could not load projects for assistant context", e);
        }
    }
}
