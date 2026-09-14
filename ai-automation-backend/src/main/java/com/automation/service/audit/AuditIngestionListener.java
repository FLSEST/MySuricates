package com.automation.service.audit;

import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuditIngestionListener {
    private final AuditIngestionService ingestionService;
    private final ModuleRegistry modules;

    @Async
    @EventListener
    public void onWorkflowRunCompleted(WorkflowRunCompletedEvent event) {
        if (!modules.isEnabled(ModuleCatalog.AUDIT)) {
            return;
        }
        Object audit = event.getWorkflowMetadata().get("audit");
        if (audit instanceof Map<?, ?> config && Boolean.TRUE.equals(config.get("enabled"))) {
            ingestionService.ingest(event);
        }
    }
}
