package com.automation.service.goal;

import com.automation.dto.projection.GoalRelationRow;
import com.automation.dto.projection.RunStateRow;
import com.automation.dto.projection.TodoTriggerMatchRow;
import com.automation.dto.response.GoalRelationsResponse.Origin;
import com.automation.dto.response.GoalRelationsResponse.OriginKind;
import com.automation.dto.response.GoalRelationsResponse.Result;
import com.automation.dto.response.GoalRelationsResponse.TodoRelation;
import com.automation.dto.response.GoalRelationsResponse.Worker;
import com.automation.dto.response.GoalRelationsResponse.WorkerKind;
import com.automation.entity.Todo;
import com.automation.mapper.TodoMapper;
import com.automation.service.trigger.TodoTriggerMatcher;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reine Zuordnung fuer die Relationskarte (TODO c6ea8f5c): aus einer {@link GoalRelationRow} und
 * den gebuendelt geladenen Nachschlagetabellen wird Herkunft, Bearbeitung und Ergebnis.
 * Kein Repository, keine Transaktion.
 */
final class GoalRelationAssembler {

    /** Referenztypen, die TODO-Erzeuger setzen ({@code AutoTodoService}, {@code ManagedTodoWriter}, {@code HealthAutoTodoService}). */
    static final String REF_WORKFLOW_RUN = "workflow_run";
    static final String REF_AUDIT_FINDING = "audit_finding";
    static final String REF_HEALTH_CHECK = "health_check";

    /** Dieselbe Arbeitsreihenfolge wie die Zielansicht: Rang, dann Prioritaet, dann Alter. */
    static final Comparator<GoalRelationRow> WORK_ORDER = GoalService.workOrder(
            GoalRelationRow::goalRank, GoalRelationRow::priority, GoalRelationRow::createdAt);

    /**
     * @param findingIds Audit-Befunde je TODO-ID aus {@code audit_finding_todos}
     * @param triggers   alle aktiven Trigger
     */
    record Lookups(Map<String, String> projectNames, Map<String, String> workflowNames, Map<String, RunStateRow> runs,
                   Map<String, List<String>> findingIds, List<TodoTriggerMatchRow> triggers) {}

    private GoalRelationAssembler() {
    }

    static TodoRelation toRelation(GoalRelationRow row, boolean candidate, Lookups lookups) {
        return new TodoRelation(row.id(), row.title(), row.status(), row.priority(), row.projectId(),
                row.projectId() == null ? null : lookups.projectNames().get(row.projectId()),
                TodoMapper.jsonToList(row.tags()), row.goalRank(), candidate,
                originOf(row, lookups), workersOf(row, lookups), resultOf(row, lookups));
    }

    /** Workflow, auf den die Referenz des TODOs zeigt ({@code workflow_run} traegt die Workflow-ID). */
    static String referencedWorkflowId(GoalRelationRow row) {
        return REF_WORKFLOW_RUN.equals(row.referenceType()) ? row.referenceId() : null;
    }

    static Origin originOf(GoalRelationRow row, Lookups lookups) {
        RunStateRow run = row.workflowRunId() == null ? null : lookups.runs().get(row.workflowRunId());
        String workflowId = run != null && run.workflowId() != null ? run.workflowId() : referencedWorkflowId(row);
        String workflowName = workflowId == null ? null : lookups.workflowNames().get(workflowId);
        if (workflowName == null && run != null) {
            workflowName = run.workflowName();
        }
        OriginKind kind = kindOf(row, lookups);
        List<String> findings = kind == OriginKind.AUDIT ? findingIdsOf(row, lookups) : List.of();
        return new Origin(kind, workflowId, workflowName, row.workflowRunId(), run == null ? null : run.status(),
                findings, row.sourceAgent());
    }

    private static OriginKind kindOf(GoalRelationRow row, Lookups lookups) {
        if (REF_AUDIT_FINDING.equals(row.referenceType()) || lookups.findingIds().containsKey(row.id())) {
            return OriginKind.AUDIT;
        }
        if (REF_HEALTH_CHECK.equals(row.referenceType())) {
            return OriginKind.HEALTH;
        }
        if (row.workflowRunId() != null || REF_WORKFLOW_RUN.equals(row.referenceType())
                || row.source() == Todo.Source.WORKFLOW) {
            return OriginKind.WORKFLOW_RUN;
        }
        return row.source() == Todo.Source.AI ? OriginKind.AGENT : OriginKind.USER;
    }

    private static List<String> findingIdsOf(GoalRelationRow row, Lookups lookups) {
        Set<String> ids = new LinkedHashSet<>();
        if (REF_AUDIT_FINDING.equals(row.referenceType()) && row.referenceId() != null) {
            ids.add(row.referenceId());
        }
        ids.addAll(lookups.findingIds().getOrDefault(row.id(), List.of()));
        return List.copyOf(ids);
    }

    /** Fix-Workflow und jeder passende aktive Trigger; Workflows, die es nicht mehr gibt, fallen weg. */
    static List<Worker> workersOf(GoalRelationRow row, Lookups lookups) {
        List<Worker> workers = new ArrayList<>();
        String linkedName = row.linkedWorkflowId() == null ? null : lookups.workflowNames().get(row.linkedWorkflowId());
        if (linkedName != null) {
            workers.add(new Worker(WorkerKind.LINKED, row.linkedWorkflowId(), linkedName, null, null));
        }
        TodoTriggerMatcher.Subject subject = new TodoTriggerMatcher.Subject(
                row.tags(), row.projectId(), row.source(), row.priority());
        for (TodoTriggerMatchRow trigger : lookups.triggers()) {
            String name = lookups.workflowNames().get(trigger.targetWorkflowId());
            TodoTriggerMatcher.Criteria criteria = new TodoTriggerMatcher.Criteria(
                    trigger.tagPattern(), trigger.projectId(), trigger.sourceFilter(), trigger.minPriority());
            if (name != null && TodoTriggerMatcher.matches(criteria, subject)) {
                workers.add(new Worker(WorkerKind.TRIGGER, trigger.targetWorkflowId(), name, trigger.id(),
                        trigger.description()));
            }
        }
        return workers;
    }

    /**
     * {@code null}, solange nie ein Fix-Lauf gestartet wurde – auch wenn ein Fix-Workflow verknuepft
     * ist: Das Verknuepfen setzt {@code IDLE} ({@code TodoService}), das ist noch kein Ergebnis
     * (TODO 05d4bfe5). Ein geloeschter Lauf hinterlaesst {@code IDLE} mit Versuchen – die bleiben sichtbar.
     */
    static Result resultOf(GoalRelationRow row, Lookups lookups) {
        boolean neverStarted = row.lastFixRunId() == null && row.fixAttempts() == 0;
        if (neverStarted && (row.resolutionState() == null || row.resolutionState() == Todo.ResolutionState.IDLE)) {
            return null;
        }
        RunStateRow run = row.lastFixRunId() == null ? null : lookups.runs().get(row.lastFixRunId());
        return new Result(row.lastFixRunId(), run == null ? null : run.workflowId(), run == null ? null : run.status(),
                run == null ? null : (run.completedAt() != null ? run.completedAt() : run.startedAt()),
                row.fixAttempts(), row.resolutionState());
    }
}
