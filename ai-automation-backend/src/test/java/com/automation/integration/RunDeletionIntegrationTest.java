package com.automation.integration;

import com.automation.entity.*;
import com.automation.repository.DeadLetterRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.RunDeletionService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves against real SQLite that a deleted run leaves nothing behind —
 * and does not drag anything foreign along.
 *
 * <p>The loose references ({@code dead_letter_entries.workflow_run_id},
 * {@code todos.workflow_run_id}, {@code todos.last_fix_run_id},
 * {@code wishes.current_run_id}) have no foreign key. Without
 * {@link RunDeletionService} they remain as dead pointers; a
 * DLQ entry would then offer a retry button that does nothing anymore.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class RunDeletionIntegrationTest {

    @Autowired private RunDeletionService runDeletionService;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private StepResultRepository stepResultRepository;
    @Autowired private DeadLetterRepository deadLetterRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private WishRepository wishRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private EntityManager entityManager;

    private Workflow workflow;

    @BeforeEach
    void setUp() {
        Project project = projectRepository.save(Project.builder()
                .name("Run Deletion Test").type(ProjectType.CODE)
                .path("/tmp/run-deletion-test").status(ProjectStatus.RUNNING).build());
        workflow = workflowRepository.save(Workflow.builder()
                .name("Deletion Test Workflow").project(project).build());
    }

    private WorkflowRun run(RunStatus status, int schritte) {
        WorkflowRun run = runRepository.save(WorkflowRun.builder()
                .workflow(workflow).workflowName(workflow.getName())
                .status(status).startedAt(Instant.now()).build());
        for (int i = 0; i < schritte; i++) {
            stepResultRepository.save(StepResult.builder()
                    .workflowRun(run).stepIndex(i).status(StepStatus.COMPLETED)
                    .output("Ausgabe " + i).build());
        }
        flush();
        return run;
    }

    /** Forces Hibernate to actually issue the SQL — otherwise the test only checks the cache. */
    private void flush() {
        entityManager.flush();
        entityManager.clear();
    }

    // ── the core: nothing stays behind ────────────────────────────────────────

    @Test
    void delete_entferntRunUndSeineSchritte() {
        WorkflowRun run = run(RunStatus.FAILED, 3);
        assertThat(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(run.getId())).hasSize(3);

        runDeletionService.delete(run.getId());
        flush();

        assertThat(runRepository.findById(run.getId())).isEmpty();
        assertThat(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(run.getId())).isEmpty();
    }

    @Test
    void delete_entferntDenDeadLetterEintrag() {
        WorkflowRun run = run(RunStatus.FAILED, 1);
        DeadLetterEntry eintrag = deadLetterRepository.save(DeadLetterEntry.builder()
                .workflowRunId(run.getId()).workflowId(workflow.getId())
                .workflowName(workflow.getName()).errorMessage("Step 2 failed")
                .lastFailureAt(Instant.now()).build());
        flush();

        runDeletionService.delete(run.getId());
        flush();

        assertThat(deadLetterRepository.findById(eintrag.getId())).isEmpty();
    }

    @Test
    void delete_laesstFremdeLaeufeUndDerenDatenUnberuehrt() {
        WorkflowRun opfer = run(RunStatus.FAILED, 2);
        WorkflowRun nachbar = run(RunStatus.COMPLETED, 2);
        DeadLetterEntry fremd = deadLetterRepository.save(DeadLetterEntry.builder()
                .workflowRunId(nachbar.getId()).workflowId(workflow.getId())
                .workflowName(workflow.getName()).errorMessage("nicht meiner")
                .lastFailureAt(Instant.now()).build());
        flush();

        runDeletionService.delete(opfer.getId());
        flush();

        assertThat(runRepository.findById(nachbar.getId())).isPresent();
        assertThat(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(nachbar.getId())).hasSize(2);
        assertThat(deadLetterRepository.findById(fremd.getId())).isPresent();
    }

    // ── foreign data is decoupled, not deleted ───────────────────────

    @Test
    void delete_nulltDenTodoVerweisAberBehaeltDasTodo() {
        WorkflowRun run = run(RunStatus.FAILED, 1);
        Todo todo = todoRepository.save(Todo.builder()
                .title("[Incident] etwas ging schief").status(Todo.Status.DONE)
                .priority(Todo.Priority.MEDIUM).source(Todo.Source.WORKFLOW)
                .workflowRunId(run.getId()).build());
        flush();

        runDeletionService.delete(run.getId());
        flush();

        Todo danach = todoRepository.findById(todo.getId()).orElseThrow();
        assertThat(danach.getWorkflowRunId()).isNull();
        assertThat(danach.getTitle()).isEqualTo("[Incident] etwas ging schief");
    }

    @Test
    void delete_holtEinHaengendesFixTodoAusDemRunningZustand() {
        WorkflowRun run = run(RunStatus.FAILED, 1);
        Todo todo = todoRepository.save(Todo.builder()
                .title("Fix mich").status(Todo.Status.IN_PROGRESS)
                .priority(Todo.Priority.HIGH).source(Todo.Source.AI)
                .lastFixRunId(run.getId())
                .resolutionState(Todo.ResolutionState.RUNNING).build());
        flush();

        runDeletionService.delete(run.getId());
        flush();

        Todo danach = todoRepository.findById(todo.getId()).orElseThrow();
        assertThat(danach.getLastFixRunId()).isNull();
        assertThat(danach.getResolutionState()).isEqualTo(Todo.ResolutionState.IDLE);
    }

    @Test
    void delete_nulltDenWunschVerweis() {
        WorkflowRun run = run(RunStatus.FAILED, 1);
        Wish wish = wishRepository.save(Wish.builder()
                .originalText("Ein Wunsch").currentRunId(run.getId()).build());
        flush();

        runDeletionService.delete(run.getId());
        flush();

        assertThat(wishRepository.findById(wish.getId()).orElseThrow().getCurrentRunId()).isNull();
    }

    // ── Guard ──────────────────────────────────────────────────────────────

    @Test
    void delete_verweigertLaufendeRuns() {
        WorkflowRun run = run(RunStatus.RUNNING, 1);

        assertThatThrownBy(() -> runDeletionService.delete(run.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("still active");

        assertThat(runRepository.findById(run.getId())).isPresent();
    }

    @Test
    void delete_verweigertPausierteRuns() {
        WorkflowRun run = run(RunStatus.PAUSED, 1);

        assertThatThrownBy(() -> runDeletionService.delete(run.getId()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(runRepository.findById(run.getId())).isPresent();
    }

    @Test
    void delete_meldetUnbekanntenRun() {
        assertThatThrownBy(() -> runDeletionService.delete("gibt-es-nicht"))
                .hasMessageContaining("not found");
    }

    @Test
    void delete_erlaubtAbgeschlosseneUndAbgebrocheneRuns() {
        for (RunStatus status : List.of(RunStatus.COMPLETED, RunStatus.FAILED,
                RunStatus.CANCELLED, RunStatus.INTERRUPTED)) {
            WorkflowRun run = run(status, 1);
            runDeletionService.delete(run.getId());
            flush();
            assertThat(runRepository.findById(run.getId()))
                    .as("Status %s muss loeschbar sein", status).isEmpty();
        }
    }
}
