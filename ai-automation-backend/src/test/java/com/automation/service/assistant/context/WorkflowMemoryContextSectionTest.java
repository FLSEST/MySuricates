package com.automation.service.assistant.context;

import com.automation.entity.Workflow;
import com.automation.entity.WorkflowMemory;
import com.automation.repository.WorkflowMemoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowMemoryContextSectionTest {

    @Mock private WorkflowMemoryRepository workflowMemoryRepository;

    private WorkflowMemoryContextSection section;

    @BeforeEach
    void setUp() {
        section = new WorkflowMemoryContextSection(workflowMemoryRepository);
    }

    @Test
    void noMemories_appendsNothing() {
        when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc()).thenReturn(List.of());

        StringBuilder sb = new StringBuilder();
        section.append(sb, Map.of());

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void knownWorkflow_usesWorkflowName() {
        WorkflowMemory memory = WorkflowMemory.builder()
                .workflowId("wf-1").content("Kontext validieren.").active(true).build();
        when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc()).thenReturn(List.of(memory));
        Workflow wf = Workflow.builder().id("wf-1").name("Build Alpha").build();

        StringBuilder sb = new StringBuilder();
        section.append(sb, Map.of("wf-1", wf));

        assertThat(sb.toString())
                .contains("## Learnings & Pitfalls")
                .contains("[Build Alpha]: Kontext validieren.");
    }

    @Test
    void unknownWorkflow_fallsBackToIdPrefix() {
        WorkflowMemory memory = WorkflowMemory.builder()
                .workflowId("0123456789abcdef").content("Learnings.").active(true).build();
        when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc()).thenReturn(List.of(memory));

        StringBuilder sb = new StringBuilder();
        section.append(sb, Map.of());

        assertThat(sb.toString()).contains("[01234567…]: Learnings.");
    }

    @Test
    void limitsToEightMemories() {
        List<WorkflowMemory> memories = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            memories.add(WorkflowMemory.builder()
                    .workflowId("wf-" + i).content("Memory " + i).active(true).build());
        }
        when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc()).thenReturn(memories);

        StringBuilder sb = new StringBuilder();
        section.append(sb, Map.of());

        assertThat(sb.toString())
                .contains("Memory 7")
                .doesNotContain("Memory 8");
    }

    @Test
    void exception_doesNotThrow() {
        when(workflowMemoryRepository.findByActiveTrueOrderByCreatedAtDesc())
                .thenThrow(new RuntimeException("DB error"));

        StringBuilder sb = new StringBuilder();
        section.append(sb, Map.of());

        assertThat(sb.toString()).isEmpty();
    }
}
