package com.automation.service.memory;

import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowMemory;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowMemoryRepository;
import com.automation.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowMemoryServiceTest {

    @Mock private WorkflowMemoryRepository memoryRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private ConversationPreprocessor preprocessor;
    @Mock private ClaudeSummarizer claudeSummarizer;
    @Mock private CodexSummarizer codexSummarizer;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;

    private WorkflowMemoryService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        // TransactionTemplate.execute() asks the manager for a status, runs the callback,
        // then commits — a mock status lets the callback run and makes commit/rollback no-ops.
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        service = new WorkflowMemoryService(
                memoryRepository, runRepository, preprocessor,
                claudeSummarizer, codexSummarizer, new ObjectMapper(), transactionManager);
    }

    @Test
    void extractKnowledge_success() throws IOException {
        StepResult step = StepResult.builder()
                .stepIndex(0).input("test").output("output").status(StepStatus.COMPLETED)
                .metadata(new HashMap<>()).build();
        WorkflowRun run = WorkflowRun.builder().id("run-1").steps(List.of(step)).build();

        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));
        when(preprocessor.preprocess(any())).thenReturn("preprocessed");
        when(claudeSummarizer.summarize("preprocessed")).thenReturn(
                new KnowledgeSummarizer.SummarizationResult("Title", "Content", List.of("tag1")));
        when(memoryRepository.save(any())).thenAnswer(inv -> {
            WorkflowMemory m = inv.getArgument(0);
            m.setId("mem-1");
            return m;
        });

        WorkflowMemory result = service.extractKnowledge("wf-1", null, "CLAUDE");

        assertThat(result.getTitle()).isEqualTo("Title");
        assertThat(result.getContent()).isEqualTo("Content");
        assertThat(result.getProvider()).isEqualTo("CLAUDE");
        assertThat(result.isActive()).isFalse();
    }

    @Test
    void extractAndActivate_deactivatesOthersAndActivatesNewMemory() throws IOException {
        StepResult step = StepResult.builder()
                .stepIndex(0).input("test").output("output").status(StepStatus.COMPLETED)
                .metadata(new HashMap<>()).build();
        WorkflowRun run = WorkflowRun.builder().id("run-1").steps(List.of(step)).build();
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));
        when(preprocessor.preprocess(any())).thenReturn("preprocessed");
        when(claudeSummarizer.summarize("preprocessed")).thenReturn(
                new KnowledgeSummarizer.SummarizationResult("Title", "Content", List.of("tag1")));
        when(memoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkflowMemory result = service.extractAndActivate("wf-1", "CLAUDE");

        assertThat(result.isActive()).isTrue();
        assertThat(result.getTitle()).isEqualTo("Title");
        verify(memoryRepository).deactivateAllForWorkflow("wf-1");
        verify(memoryRepository, times(2)).save(any(WorkflowMemory.class));
    }

    @Test
    void extractKnowledge_unknownRunId_throwsEntityNotFound_beforeSummarizing() {
        when(runRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.extractKnowledge("wf-1", List.of("missing"), "CLAUDE"))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class)
                .hasMessageContaining("missing");
        verifyNoInteractions(claudeSummarizer);
        verify(memoryRepository, never()).save(any());
    }

    @Test
    void extractKnowledge_withSpecificRunIds() throws IOException {
        StepResult step = StepResult.builder()
                .stepIndex(0).input("x").output("y").status(StepStatus.COMPLETED)
                .metadata(new HashMap<>()).build();
        WorkflowRun run = WorkflowRun.builder().id("run-1").steps(List.of(step)).build();

        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(preprocessor.preprocess(any())).thenReturn("data");
        when(claudeSummarizer.summarize("data")).thenReturn(
                new KnowledgeSummarizer.SummarizationResult("T", "C", List.of()));
        when(memoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.extractKnowledge("wf-1", List.of("run-1"), "CLAUDE");

        verify(runRepository).findById("run-1");
        verify(runRepository, never()).findByWorkflowIdOrderByStartedAtDesc(anyString());
    }

    @Test
    void extractKnowledge_noRunsThrows() {
        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of());

        assertThatThrownBy(() -> service.extractKnowledge("wf-1", null, "CLAUDE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No runs found");
    }

    @Test
    void extractKnowledge_unknownProviderThrows() {
        StepResult step = StepResult.builder()
                .stepIndex(0).input("x").output("y").status(StepStatus.COMPLETED)
                .metadata(new HashMap<>()).build();
        WorkflowRun run = WorkflowRun.builder().id("run-1").steps(List.of(step)).build();

        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));
        when(preprocessor.preprocess(any())).thenReturn("data");

        assertThatThrownBy(() -> service.extractKnowledge("wf-1", null, "UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown provider");
    }

    @Test
    void extractKnowledge_codexProvider() throws IOException {
        StepResult step = StepResult.builder()
                .stepIndex(0).input("x").output("y").status(StepStatus.COMPLETED)
                .metadata(new HashMap<>()).build();
        WorkflowRun run = WorkflowRun.builder().id("run-1").steps(List.of(step)).build();

        when(runRepository.findByWorkflowIdOrderByStartedAtDesc("wf-1")).thenReturn(List.of(run));
        when(preprocessor.preprocess(any())).thenReturn("data");
        when(codexSummarizer.summarize("data")).thenReturn(
                new KnowledgeSummarizer.SummarizationResult("Codex Title", "Content", List.of("ai")));
        when(memoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkflowMemory result = service.extractKnowledge("wf-1", null, "CODEX");

        assertThat(result.getTitle()).isEqualTo("Codex Title");
        assertThat(result.getProvider()).isEqualTo("CODEX");
        verify(codexSummarizer).summarize("data");
    }

    @Test
    void activate_success() {
        WorkflowMemory memory = WorkflowMemory.builder()
                .id("mem-1").workflowId("wf-1").active(false).build();
        when(memoryRepository.findById("mem-1")).thenReturn(Optional.of(memory));
        when(memoryRepository.save(any())).thenReturn(memory);

        service.activate("wf-1", "mem-1");

        verify(memoryRepository).deactivateAllForWorkflow("wf-1");
        assertThat(memory.isActive()).isTrue();
    }

    @Test
    void activate_wrongWorkflowThrows() {
        WorkflowMemory memory = WorkflowMemory.builder()
                .id("mem-1").workflowId("wf-other").build();
        when(memoryRepository.findById("mem-1")).thenReturn(Optional.of(memory));

        assertThatThrownBy(() -> service.activate("wf-1", "mem-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writeActiveMemoryToProject_writesFile() {
        String content = "## Memory content";
        WorkflowMemory memory = WorkflowMemory.builder()
                .id("mem-1").workflowId("wf-1").content(content).active(true).build();
        when(memoryRepository.findByWorkflowIdAndActiveTrue("wf-1")).thenReturn(Optional.of(memory));

        service.writeActiveMemoryToProject("wf-1", tempDir.toString());

        Path memoryFile = tempDir.resolve(".claude/rules/project-memory.md");
        assertThat(memoryFile).exists();
        assertThat(memoryFile).hasContent(content);
    }

    @Test
    void writeActiveMemoryToProject_deletesStaleFile() throws IOException {
        Path memoryFile = tempDir.resolve(".claude/rules/project-memory.md");
        Files.createDirectories(memoryFile.getParent());
        Files.writeString(memoryFile, "old content");

        when(memoryRepository.findByWorkflowIdAndActiveTrue("wf-1")).thenReturn(Optional.empty());

        service.writeActiveMemoryToProject("wf-1", tempDir.toString());

        assertThat(memoryFile).doesNotExist();
    }

    @Test
    void writeActiveMemoryToProject_nullPathSkips() {
        service.writeActiveMemoryToProject("wf-1", null);
        verify(memoryRepository, never()).findByWorkflowIdAndActiveTrue(anyString());
    }

    @Test
    void delete_success() {
        WorkflowMemory memory = WorkflowMemory.builder()
                .id("mem-1").workflowId("wf-1").build();
        when(memoryRepository.findById("mem-1")).thenReturn(Optional.of(memory));

        service.delete("wf-1", "mem-1");

        verify(memoryRepository).delete(memory);
    }

    @Test
    void delete_notFoundThrows() {
        when(memoryRepository.findById("mem-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete("wf-1", "mem-1"))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
