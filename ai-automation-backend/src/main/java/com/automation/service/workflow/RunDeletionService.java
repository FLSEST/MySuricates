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
 * Loescht einen einzelnen Workflow-Run samt seiner Spuren.
 *
 * <p>Abgrenzung zu {@link RunCleanupService}: der raeumt nachts automatisch
 * INTERRUPTED-Laeufe weg und verlaesst sich dabei ganz auf die JPA-Kaskade.
 * Hier loescht ein Mensch bewusst einen bestimmten Lauf — auch einen
 * fehlgeschlagenen, und der hat im Gegensatz zu einem abgebrochenen fast immer
 * einen Dead-Letter-Eintrag und manchmal ein Incident-TODO an sich haengen.
 *
 * <p>Was von selbst mitgeht: {@code step_results} (Kaskade plus
 * {@code ON DELETE CASCADE}) und {@code template_verifications.last_run_id}
 * ({@code ON DELETE SET NULL}). Notizen liegen im {@code metadata}-JSON des
 * Runs und sterben mit der Zeile.
 *
 * <p>Was hier von Hand geschieht, weil es nur lose Textspalten ohne
 * Fremdschluessel sind: Dead-Letter-Eintraege, TODO-Verweise, Wunsch-Verweise.
 *
 * <p>Fremde Datensaetze werden nie mitgeloescht. Ein TODO verliert seinen
 * Verweis, aber nicht sein Leben; haengt ein Audit-Bericht am Lauf, verweigert
 * der Dienst die Loeschung, statt Beweisdaten zu verwaisen.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunDeletionService {

    /** Laeufe in diesen Zustaenden sind noch in Bewegung und werden nicht angetastet. */
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
     * Loescht den Lauf und alle losen Verweise darauf.
     *
     * @throws ResourceNotFoundException wenn es den Lauf nicht gibt
     * @throws IllegalStateException     wenn er noch laeuft oder Audit-Beweise
     *                                   an ihm haengen (wird zu HTTP 409)
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

        // Bewusst protokolliert: wir raeumen die Historie auf, wir vertuschen sie nicht.
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
        // audit_reports.workflow_run_id und audit_findings.first/last_seen_run_id sind
        // NOT NULL. Ein Loeschen wuerde dort unaufloesbare Verweise hinterlassen.
        if (auditReportRepository.existsByWorkflowRunId(run.getId())) {
            throw new IllegalStateException(
                    "Run " + run.getId() + " is referenced by an audit report — "
                            + "delete the audit report first");
        }
    }

    /**
     * Nimmt den Verweis aus den TODOs, laesst die TODOs selbst stehen.
     *
     * <p>Ein bei {@code RUNNING} stehengebliebener Reparaturlauf wird auf
     * {@code IDLE} zurueckgesetzt: ohne den Run kann {@code isFixRunStillRunning}
     * ihn nicht mehr pruefen, und das TODO haenge sonst dauerhaft in einem
     * Zustand, aus dem es nicht mehr herauskommt.
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
