package com.automation.integration;

import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowMemory;
import com.automation.entity.WorkflowRun;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WorkflowMemoryRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.memory.ClaudeSummarizer;
import com.automation.service.memory.KnowledgeSummarizer;
import com.automation.service.memory.WorkflowMemoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Knowledge extraction must not hold a transaction around the AI summarization call
 * (Finding 4, sec-memory — same shape as {@code ebd508c5} for {@code ProjectService}).
 * {@code WorkflowMemoryService} reads runs and steps from SQLite (HikariCP pool-size 1) and
 * then calls out to Claude/Codex on the host, a call that takes seconds to minutes. An
 * enclosing transaction would pin the single connection for that whole call, starving every
 * other DB access in the app. Runs against the real transaction manager and asserts, from
 * inside the mocked summarizer, that no transaction is active — for both
 * {@code extractKnowledge} and {@code extractAndActivate}.
 */
@SpringBootTest
@ActiveProfiles("integration")
class WorkflowMemoryExtractionIntegrationTest {

    @Autowired private WorkflowMemoryService memoryService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private WorkflowMemoryRepository memoryRepository;
    @MockitoBean private ClaudeSummarizer claudeSummarizer;

    private final List<Runnable> cleanup = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (int i = cleanup.size() - 1; i >= 0; i--) {
            try { cleanup.get(i).run(); } catch (Exception ignored) { /* already gone */ }
        }
        cleanup.clear();
    }

    private Workflow persistedWorkflowWithRun() {
        long nano = System.nanoTime();
        Project project = projectRepository.save(Project.builder()
                .name("memory-it-" + nano).type(ProjectType.CODE)
                .path("/tmp/memory-it-" + nano).status(ProjectStatus.STOPPED).build());
        cleanup.add(() -> projectRepository.deleteById(project.getId()));

        Workflow workflow = workflowRepository.save(Workflow.builder()
                .name("memory-it-workflow-" + nano).project(project).build());
        cleanup.add(() -> workflowRepository.deleteById(workflow.getId()));

        WorkflowRun run = WorkflowRun.builder()
                .workflow(workflow)
                .status(RunStatus.COMPLETED)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .build();
        StepResult step = new StepResult();
        step.setStatus(StepStatus.COMPLETED);
        step.setOutput("step output");
        run.addStep(step);
        WorkflowRun savedRun = runRepository.save(run);
        cleanup.add(() -> runRepository.deleteById(savedRun.getId()));

        return workflow;
    }

    @Test
    void extractKnowledge_doesNotHoldTransactionDuringSummarization() throws Exception {
        Workflow workflow = persistedWorkflowWithRun();
        AtomicReference<Boolean> txDuringSummarize = new AtomicReference<>();
        when(claudeSummarizer.summarize(any())).thenAnswer(inv -> {
            txDuringSummarize.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new KnowledgeSummarizer.SummarizationResult("Title", "Content", List.of("tag"));
        });

        WorkflowMemory memory = memoryService.extractKnowledge(workflow.getId(), null, "CLAUDE");
        cleanup.add(() -> memoryRepository.deleteById(memory.getId()));

        assertThat(txDuringSummarize.get()).isFalse();
        assertThat(memory.getTitle()).isEqualTo("Title");
        assertThat(memory.isActive()).isFalse();
    }

    @Test
    void extractAndActivate_doesNotHoldTransactionDuringSummarization() throws Exception {
        Workflow workflow = persistedWorkflowWithRun();
        AtomicReference<Boolean> txDuringSummarize = new AtomicReference<>();
        when(claudeSummarizer.summarize(any())).thenAnswer(inv -> {
            txDuringSummarize.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new KnowledgeSummarizer.SummarizationResult("Auto Title", "Auto Content", List.of());
        });

        WorkflowMemory memory = memoryService.extractAndActivate(workflow.getId(), "CLAUDE");
        cleanup.add(() -> memoryRepository.deleteById(memory.getId()));

        assertThat(txDuringSummarize.get()).isFalse();
        assertThat(memory.isActive()).isTrue();
        assertThat(memoryRepository.findByWorkflowIdAndActiveTrue(workflow.getId()))
                .map(WorkflowMemory::getId)
                .contains(memory.getId());
    }
}
