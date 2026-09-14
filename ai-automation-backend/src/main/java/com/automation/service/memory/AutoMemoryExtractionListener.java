package com.automation.service.memory;

import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowMemory;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.WorkflowMemoryRepository;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Automatically extracts and activates workflow memory after successful runs.
 * Configured per-workflow via metadata: autoMemoryExtraction + autoMemoryInterval.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoMemoryExtractionListener {

    private static final int DEFAULT_INTERVAL = 5;

    private final WorkflowMemoryService memoryService;
    private final WorkflowMemoryRepository memoryRepository;
    private final WorkflowRunRepository runRepository;
    private final ModuleRegistry modules;

    @Async
    @EventListener
    public void onWorkflowRunCompleted(WorkflowRunCompletedEvent event) {
        // Memory module off ⇒ no AI extraction calls, whatever the workflow metadata says
        if (!modules.isEnabled(ModuleCatalog.MEMORY)) return;

        // Only trigger on successful runs
        if (event.getStatus() != RunStatus.COMPLETED) return;

        // Check workflow metadata for auto-extraction flag
        if (!isAutoMemoryEnabled(event.getWorkflowMetadata())) return;

        int interval = getInterval(event.getWorkflowMetadata());
        String workflowId = event.getWorkflowId();

        // Count successful runs since last memory extraction
        Optional<WorkflowMemory> latestMemory = memoryRepository.findByWorkflowIdOrderByCreatedAtDesc(workflowId)
                .stream().findFirst();
        Instant since = latestMemory.map(WorkflowMemory::getCreatedAt).orElse(Instant.EPOCH);
        long runsSinceLastExtraction = runRepository.countByWorkflowIdAndStatusAndCompletedAtAfter(
                workflowId, RunStatus.COMPLETED, since);

        if (runsSinceLastExtraction < interval) {
            log.debug("Auto-memory: {} successful runs since last extraction for workflow {} (need {})",
                    runsSinceLastExtraction, workflowId, interval);
            return;
        }

        try {
            log.info("Auto-memory extraction triggered for workflow '{}' ({} runs since last extraction)",
                    event.getWorkflowName(), runsSinceLastExtraction);
            WorkflowMemory memory = memoryService.extractAndActivate(workflowId, "CLAUDE");

            // Write to project if path is available
            if (event.getProjectPath() != null) {
                memoryService.writeActiveMemoryToProject(workflowId, event.getProjectPath());
            }

            log.info("Auto-memory extraction completed for workflow '{}': '{}'",
                    event.getWorkflowName(), memory.getTitle());
        } catch (Exception e) {
            log.warn("Auto-memory extraction failed for workflow '{}': {}",
                    event.getWorkflowName(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private boolean isAutoMemoryEnabled(Map<String, Object> workflowMetadata) {
        Object autoMemory = workflowMetadata.get("autoMemoryExtraction");
        if (autoMemory instanceof Boolean b) return b;
        if (autoMemory instanceof Map<?, ?> map) {
            return Boolean.TRUE.equals(map.get("enabled"));
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private int getInterval(Map<String, Object> workflowMetadata) {
        Object autoMemory = workflowMetadata.get("autoMemoryExtraction");
        if (autoMemory instanceof Map<?, ?> map) {
            Object interval = map.get("interval");
            if (interval instanceof Number n) return Math.max(1, n.intValue());
        }
        return DEFAULT_INTERVAL;
    }
}
