package com.automation.service.assistant.context;

import com.automation.entity.Workflow;
import com.automation.entity.WorkflowMemory;
import com.automation.repository.WorkflowMemoryRepository;
import com.automation.service.assistant.AssistantFormatUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Lists active workflow memories ("Learnings &amp; Pitfalls", max 8) with the
 * owning workflow's name resolved from the shared workflow map.
 *
 * <p>Not an {@link AssistantContextSection}: it needs the workflow-by-id map the
 * builder derives from the workflow list loaded once and shared across sections,
 * so the standard two-argument signature does not fit.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowMemoryContextSection {

    private final WorkflowMemoryRepository workflowMemoryRepository;

    /**
     * Appends the workflow memory section.
     *
     * @param sb           the prompt being built
     * @param workflowById pre-loaded workflows keyed by id, shared across sections
     */
    public void append(StringBuilder sb, Map<String, Workflow> workflowById) {
        try {
            List<WorkflowMemory> memories = workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc();
            if (memories.isEmpty()) return;
            sb.append("## Learnings & Pitfalls\n");
            memories.stream().limit(8).forEach(m -> {
                String wfName = workflowById.containsKey(m.getWorkflowId())
                        ? workflowById.get(m.getWorkflowId()).getName()
                        : m.getWorkflowId().substring(0, Math.min(8, m.getWorkflowId().length())) + "…";
                sb.append("  - [").append(wfName).append("]: ")
                        .append(AssistantFormatUtils.truncate(m.getContent(), 300)).append("\n");
            });
            sb.append("\n");
        } catch (Exception e) {
            log.debug("Could not load workflow memory for assistant context", e);
        }
    }
}
