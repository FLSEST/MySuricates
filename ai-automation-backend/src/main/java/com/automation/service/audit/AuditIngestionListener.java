package com.automation.service.audit;

import com.automation.event.WorkflowRunCompletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuditIngestionListener {
    private final AuditIngestionService ingestionService;

    @Async
    @EventListener
    public void onWorkflowRunCompleted(WorkflowRunCompletedEvent event) {
        Object audit = event.getWorkflowMetadata().get("audit");
        if (audit instanceof Map<?, ?> config && Boolean.TRUE.equals(config.get("enabled"))) {
            ingestionService.ingest(event);
        }
    }
}
