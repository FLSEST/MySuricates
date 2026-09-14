package com.automation.service.audit;

import com.automation.entity.RunStatus;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.modules.ModuleRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class AuditIngestionListenerTest {

    @Mock private AuditIngestionService ingestionService;

    @Test
    void ingestsAuditEnabledRunsWhenModuleIsOn() {
        var listener = new AuditIngestionListener(ingestionService, ModuleRegistry.allEnabled());
        var event = event(Map.of("audit", Map.of("enabled", true)));

        listener.onWorkflowRunCompleted(event);

        verify(ingestionService).ingest(event);
    }

    @Test
    void skipsRunsWithoutAuditMetadata() {
        var listener = new AuditIngestionListener(ingestionService, ModuleRegistry.allEnabled());

        listener.onWorkflowRunCompleted(event(Map.of()));

        verify(ingestionService, never()).ingest(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsEverythingWhenAuditModuleIsOff() {
        var listener = new AuditIngestionListener(ingestionService, ModuleRegistry.of(Map.of(), Map.of()));

        listener.onWorkflowRunCompleted(event(Map.of("audit", Map.of("enabled", true))));

        verifyNoInteractions(ingestionService);
    }

    private WorkflowRunCompletedEvent event(Map<String, Object> workflowMeta) {
        return new WorkflowRunCompletedEvent(this, "run-1", "wf-1", "Audit Workflow",
                "proj-1", "/project/path", RunStatus.COMPLETED, Map.of(), workflowMeta, Map.of());
    }
}
