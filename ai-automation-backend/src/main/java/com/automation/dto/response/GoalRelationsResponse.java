package com.automation.dto.response;

import com.automation.entity.RunStatus;
import com.automation.entity.Todo;

import java.time.Instant;
import java.util.List;

/**
 * Relation map of a goal (TODO c6ea8f5c): for each TODO, where it comes from, which workflow
 * processes it or would process it, and what the last fix run produced. A goal is never
 * directly linked to workflows - only via these three relations of its TODOs.
 *
 * @param activeTriggers total number of active TodoTriggers; 0 means no TODO can dock on via
 *                       trigger
 * @param todos          the goal's TODOs in working order, followed by the requested
 *                       candidates ({@code candidate = true}) in request order
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

    /** Where a TODO comes from - checked in this order, the first match counts. */
    public enum OriginKind { AUDIT, HEALTH, WORKFLOW_RUN, AGENT, USER }

    /**
     * @param workflowId   workflow of the creating run (for audit: the audit workflow), else null
     * @param workflowName current name, otherwise the one recorded at the run
     * @param runStatus    status of the creating run; null if the run no longer exists
     * @param findingIds   audit findings pointing at the TODO (only for {@link OriginKind#AUDIT})
     * @param agent        {@code sourceAgent}, e.g. {@code claude-code}
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

    /** {@code LINKED}: fix workflow on the TODO; {@code TRIGGER}: an active TodoTrigger matches. */
    public enum WorkerKind { LINKED, TRIGGER }

    public record Worker(
            WorkerKind kind,
            String workflowId,
            String workflowName,
            String triggerId,
            String triggerLabel
    ) {}

    /**
     * Result of the processing; absent entirely as long as no fix run was ever started.
     *
     * @param workflowId workflow of the fix run - the map draws the line from its processing node
     * @param at         end of the run, while it is running its start
     */
    public record Result(
            String runId,
            String workflowId,
            RunStatus runStatus,
            Instant at,
            int fixAttempts,
            Todo.ResolutionState resolutionState
    ) {}
}
