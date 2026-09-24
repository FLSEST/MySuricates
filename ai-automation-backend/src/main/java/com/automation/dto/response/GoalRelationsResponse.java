package com.automation.dto.response;

import com.automation.entity.RunStatus;
import com.automation.entity.Todo;

import java.time.Instant;
import java.util.List;

/**
 * Relationskarte eines Ziels (TODO c6ea8f5c): je TODO, woher es kommt, welcher Workflow es
 * bearbeitet oder bearbeiten wuerde, und was der letzte Fix-Lauf ergab. Ein Ziel ist mit Workflows
 * nie direkt verknuepft – nur ueber diese drei Beziehungen seiner TODOs.
 *
 * @param activeTriggers Zahl der aktiven TodoTrigger insgesamt; 0 heisst, kein TODO kann per
 *                       Trigger andocken
 * @param todos          die TODOs des Ziels in Arbeitsreihenfolge, danach die angefragten
 *                       Kandidaten ({@code candidate = true}) in Anfragereihenfolge
 */
public record GoalRelationsResponse(String goalId, int activeTriggers, List<TodoRelation> todos) {

    public record TodoRelation(
            String id,
            String title,
            Todo.Status status,
            Todo.Priority priority,
            String projectId,
            String projectName,
            List<String> tags,
            Integer goalRank,
            boolean candidate,
            Origin origin,
            List<Worker> workers,
            Result result
    ) {}

    /** Woher ein TODO kommt – in dieser Reihenfolge geprueft, der erste Treffer zaehlt. */
    public enum OriginKind { AUDIT, HEALTH, WORKFLOW_RUN, AGENT, USER }

    /**
     * @param workflowId   Workflow des erzeugenden Laufs (bei Audit: der Audit-Workflow), sonst null
     * @param workflowName aktueller Name, sonst der beim Lauf festgehaltene
     * @param runStatus    Status des erzeugenden Laufs; null, wenn der Lauf nicht mehr existiert
     * @param findingIds   Audit-Befunde, die auf das TODO zeigen (nur bei {@link OriginKind#AUDIT})
     * @param agent        {@code sourceAgent}, etwa {@code claude-code}
     */
    public record Origin(
            OriginKind kind,
            String workflowId,
            String workflowName,
            String runId,
            RunStatus runStatus,
            List<String> findingIds,
            String agent
    ) {}

    /** {@code LINKED}: Fix-Workflow am TODO; {@code TRIGGER}: ein aktiver TodoTrigger passt. */
    public enum WorkerKind { LINKED, TRIGGER }

    public record Worker(
            WorkerKind kind,
            String workflowId,
            String workflowName,
            String triggerId,
            String triggerLabel
    ) {}

    /**
     * Ergebnis der Bearbeitung; fehlt ganz, solange nie ein Fix-Lauf gestartet wurde.
     *
     * @param at Ende des Laufs, solange er laeuft sein Start
     */
    public record Result(
            String runId,
            RunStatus runStatus,
            Instant at,
            int fixAttempts,
            Todo.ResolutionState resolutionState
    ) {}
}
