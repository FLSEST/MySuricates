package com.automation.service.memory;

import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowMemory;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.WorkflowMemoryRepository;
import com.automation.repository.WorkflowRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutoMemoryExtractionListenerTest {

    @Mock private WorkflowMemoryService memoryService;
    @Mock private WorkflowMemoryRepository memoryRepository;
    @Mock private WorkflowRunRepository runRepository;

    private AutoMemoryExtractionListener listener;

    @BeforeEach
    void setUp() {
        listener = new AutoMemoryExtractionListener(memoryService, memoryRepository, runRepository,
                ModuleRegistry.allEnabled());
    }

    @Test
    void onCompleted_memoryModuleDisabled_skipsEvenWhenWorkflowOptsIn() throws Exception {
        var coreOnly = new AutoMemoryExtractionListener(memoryService, memoryRepository, runRepository,
                ModuleRegistry.of(Map.of(), Map.of()));
        var event = createEvent(RunStatus.COMPLETED, Map.of("autoMemoryExtraction", true));

        coreOnly.onWorkflowRunCompleted(event);

        verifyNoInteractions(memoryService, memoryRepository, runRepository);
    }

    @Test
    void onCompleted_autoMemoryEnabled_intervalReached_extractsAndActivates() throws Exception { // IOException from extractAndActivate
        var event = createEvent(RunStatus.COMPLETED, Map.of("autoMemoryExtraction", true));

        when(memoryRepository.findByWorkflowIdOrderByCreatedAtDesc("wf-1")).thenReturn(List.of());
        when(runRepository.countByWorkflowIdAndStatusAndCompletedAtAfter(eq("wf-1"), eq(RunStatus.COMPLETED), any()))
                .thenReturn(5L);
        when(memoryService.extractAndActivate("wf-1", "CLAUDE"))
                .thenReturn(WorkflowMemory.builder().title("Test Memory").build());

        listener.onWorkflowRunCompleted(event);

        verify(memoryService).extractAndActivate("wf-1", "CLAUDE");
    }

    @Test
    void onCompleted_autoMemoryEnabled_intervalNotReached_skips() throws Exception {
        var event = createEvent(RunStatus.COMPLETED, Map.of("autoMemoryExtraction", true));

        when(memoryRepository.findByWorkflowIdOrderByCreatedAtDesc("wf-1")).thenReturn(List.of());
        when(runRepository.countByWorkflowIdAndStatusAndCompletedAtAfter(eq("wf-1"), eq(RunStatus.COMPLETED), any()))
                .thenReturn(3L);

        listener.onWorkflowRunCompleted(event);

        verify(memoryService, never()).extractAndActivate(any(), any());
    }

    @Test
    void onCompleted_autoMemoryDisabled_skips() throws Exception {
        var event = createEvent(RunStatus.COMPLETED, Map.of());

        listener.onWorkflowRunCompleted(event);

        verify(memoryRepository, never()).findByWorkflowIdOrderByCreatedAtDesc(any());
        verify(memoryService, never()).extractAndActivate(any(), any());
    }

    @Test
    void onFailed_autoMemoryEnabled_skips() throws Exception {
        var event = createEvent(RunStatus.FAILED, Map.of("autoMemoryExtraction", true));

        listener.onWorkflowRunCompleted(event);

        verify(memoryService, never()).extractAndActivate(any(), any());
    }

    @Test
    void onCompleted_customInterval_respected() throws Exception {
        Map<String, Object> meta = Map.of("autoMemoryExtraction", Map.of("enabled", true, "interval", 10));
        var event = createEvent(RunStatus.COMPLETED, meta);

        when(memoryRepository.findByWorkflowIdOrderByCreatedAtDesc("wf-1")).thenReturn(List.of());
        when(runRepository.countByWorkflowIdAndStatusAndCompletedAtAfter(eq("wf-1"), eq(RunStatus.COMPLETED), any()))
                .thenReturn(9L);

        listener.onWorkflowRunCompleted(event);

        verify(memoryService, never()).extractAndActivate(any(), any());
    }

    @Test
    void onCompleted_customIntervalReached_extracts() throws Exception {
        Map<String, Object> meta = Map.of("autoMemoryExtraction", Map.of("enabled", true, "interval", 3));
        var event = createEvent(RunStatus.COMPLETED, meta);

        when(memoryRepository.findByWorkflowIdOrderByCreatedAtDesc("wf-1")).thenReturn(List.of());
        when(runRepository.countByWorkflowIdAndStatusAndCompletedAtAfter(eq("wf-1"), eq(RunStatus.COMPLETED), any()))
                .thenReturn(3L);
        when(memoryService.extractAndActivate("wf-1", "CLAUDE"))
                .thenReturn(WorkflowMemory.builder().title("Test").build());

        listener.onWorkflowRunCompleted(event);

        verify(memoryService).extractAndActivate("wf-1", "CLAUDE");
    }

    @Test
    void onCompleted_withProjectPath_writesToProject() throws Exception {
        var event = createEvent(RunStatus.COMPLETED, Map.of("autoMemoryExtraction", true));

        when(memoryRepository.findByWorkflowIdOrderByCreatedAtDesc("wf-1")).thenReturn(List.of());
        when(runRepository.countByWorkflowIdAndStatusAndCompletedAtAfter(eq("wf-1"), eq(RunStatus.COMPLETED), any()))
                .thenReturn(5L);
        when(memoryService.extractAndActivate("wf-1", "CLAUDE"))
                .thenReturn(WorkflowMemory.builder().title("Test").build());

        listener.onWorkflowRunCompleted(event);

        verify(memoryService).writeActiveMemoryToProject("wf-1", "/project/path");
    }

    @Test
    void onCompleted_existingMemory_countsSinceLastExtraction() throws Exception {
        var event = createEvent(RunStatus.COMPLETED, Map.of("autoMemoryExtraction", true));

        Instant memoryCreated = Instant.parse("2026-04-10T12:00:00Z");
        WorkflowMemory existing = WorkflowMemory.builder()
                .workflowId("wf-1").createdAt(memoryCreated).build();
        when(memoryRepository.findByWorkflowIdOrderByCreatedAtDesc("wf-1")).thenReturn(List.of(existing));
        when(runRepository.countByWorkflowIdAndStatusAndCompletedAtAfter("wf-1", RunStatus.COMPLETED, memoryCreated))
                .thenReturn(5L);
        when(memoryService.extractAndActivate("wf-1", "CLAUDE"))
                .thenReturn(WorkflowMemory.builder().title("New").build());

        listener.onWorkflowRunCompleted(event);

        verify(runRepository).countByWorkflowIdAndStatusAndCompletedAtAfter("wf-1", RunStatus.COMPLETED, memoryCreated);
        verify(memoryService).extractAndActivate("wf-1", "CLAUDE");
    }

    @Test
    void onCompleted_extractionFails_logsWarning() throws Exception {
        var event = createEvent(RunStatus.COMPLETED, Map.of("autoMemoryExtraction", true));

        when(memoryRepository.findByWorkflowIdOrderByCreatedAtDesc("wf-1")).thenReturn(List.of());
        when(runRepository.countByWorkflowIdAndStatusAndCompletedAtAfter(eq("wf-1"), eq(RunStatus.COMPLETED), any()))
                .thenReturn(5L);
        when(memoryService.extractAndActivate("wf-1", "CLAUDE"))
                .thenThrow(new RuntimeException("Summarization timeout"));

        // Should not throw
        listener.onWorkflowRunCompleted(event);

        verify(memoryService).extractAndActivate("wf-1", "CLAUDE");
    }

    private WorkflowRunCompletedEvent createEvent(RunStatus status, Map<String, Object> workflowMeta) {
        return new WorkflowRunCompletedEvent(
                this, "run-1", "wf-1", "Test Workflow",
                "proj-1", "/project/path", status,
                Map.of(), workflowMeta, Map.of()
        );
    }
}
