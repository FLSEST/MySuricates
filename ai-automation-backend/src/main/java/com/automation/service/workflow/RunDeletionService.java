package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.entity.Wish;
import com.automation.entity.WorkflowRun;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.AuditReportRepository;
import com.automation.repository.DeadLetterRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Deletes a single workflow run along with its traces.
 *
 * <p>Distinction from {@link RunCleanupService}: that one automatically removes
 * INTERRUPTED runs at night and relies entirely on the JPA cascade.
 * Here a human deliberately deletes a specific run - also a
 * failed one, and unlike an aborted one it almost always has
 * a dead-letter entry and sometimes an incident TODO attached.
 *
 * <p>What goes along automatically: {@code step_results} (cascade plus
 * {@code ON DELETE CASCADE}) and {@code template_verifications.last_run_id}
 * ({@code ON DELETE SET NULL}). Notes live in the {@code metadata} JSON of the
 * run and die with the row.
 *
 * <p>What is done by hand here because they are only loose text columns without
 * foreign keys: dead-letter entries, TODO references, wish references.
 *
 * <p>Foreign records are never deleted along. A TODO loses its
 * reference but not its life; if an audit report hangs on the run, the
 * service refuses the deletion instead of orphaning evidence data.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunDeletionService {

    /** Runs in these states are still in motion and are not touched. */
    private static final Set<RunStatus> LEBENDIG =
            EnumSet.of(RunStatus.PENDING, RunStatus.RUNNING, RunStatus.PAUSED);

    private final WorkflowRunRepository runRepository;
    private final DeadLetterRepository deadLetterRepository;
    private final TodoRepository todoRepository;
    private final WishRepository wishRepository;
    private final AuditReportRepository auditReportRepository;
    private final WorkflowExecutionRegistry executionRegistry;
    private final ActivityLogService activityLogService;

    /**
     * Deletes the run and all loose references to it.
     *
     * @throws ResourceNotFoundException if the run does not exist
     * @throws IllegalStateException     if it is still running or audit evidence
     *                                   hangs on it (becomes HTTP 409)
     */
    @Transactional
    public void delete(String runId) {
        WorkflowRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowRun", runId));

        pruefeLoeschbar(run);

        int dlq = deadLetterRepository.deleteByWorkflowRunId(runId);
        int todos = loeseTodoVerweise(runId);
        int wuensche = loeseWunschVerweise(runId);

        String workflowName = run.getWorkflowName();
        String workflowId = run.getWorkflow() != null ? run.getWorkflow().getId() : null;
        int schritte = run.getSteps() != null ? run.getSteps().size() : 0;

        runRepository.delete(run);

        log.info("Deleted run {} ({}, status {}): {} steps, {} DLQ entries, {} todo refs, {} wish refs",
                runId, workflowName, run.getStatus(), schritte, dlq, todos, wuensche);

        // Logged on purpose: we tidy up the history, we do not cover it up.
        activityLogService.info(LogCategory.EXECUTION,
                "Run geloescht: " + workflowName + " (" + run.getStatus() + ", "
                        + schritte + " Schritte, " + dlq + " DLQ-Eintraege)",
                "RunDeletionService", runId, workflowId);
    }

    private void pruefeLoeschbar(WorkflowRun run) {
        if (LEBENDIG.contains(run.getStatus()) || executionRegistry.isRunActive(run.getId())) {
            throw new IllegalStateException(
                    "Run " + run.getId() + " is still active (" + run.getStatus()
                            + ") — cancel it before deleting");
        }
        // audit_reports.workflow_run_id and audit_findings.first/last_seen_run_id are
        // NOT NULL. Deleting would leave unresolvable references there.
        if (auditReportRepository.existsByWorkflowRunId(run.getId())) {
            throw new IllegalStateException(
                    "Run " + run.getId() + " is referenced by an audit report — "
                            + "delete the audit report first");
        }
    }

    /**
     * Removes the reference from the TODOs, leaves the TODOs themselves in place.
     *
     * <p>A repair run stuck at {@code RUNNING} is reset to
     * {@code IDLE}: without the run, {@code isFixRunStillRunning}
     * can no longer check it, and the TODO would otherwise hang permanently in a
     * state it can no longer get out of.
     */
    private int loeseTodoVerweise(String runId) {
        List<Todo> betroffen = todoRepository.findByWorkflowRunIdOrLastFixRunId(runId, runId);
        for (Todo todo : betroffen) {
            if (runId.equals(todo.getWorkflowRunId())) {
                todo.setWorkflowRunId(null);
            }
            if (runId.equals(todo.getLastFixRunId())) {
                todo.setLastFixRunId(null);
                if (todo.getResolutionState() == Todo.ResolutionState.RUNNING) {
                    todo.setResolutionState(Todo.ResolutionState.IDLE);
                }
            }
        }
        todoRepository.saveAll(betroffen);
        return betroffen.size();
    }

    private int loeseWunschVerweise(String runId) {
        List<Wish> betroffen = wishRepository.findByCurrentRunId(runId);
        for (Wish wish : betroffen) {
            wish.setCurrentRunId(null);
        }
        wishRepository.saveAll(betroffen);
        return betroffen.size();
    }
}
