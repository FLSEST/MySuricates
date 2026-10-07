package com.automation.service.todo;

import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.repository.WorkflowRunRepository;

/**
 * Whether the last fix run of a todo is still under way — the guard against a second fix run for
 * the same todo, shared by the todo trigger and "resolve".
 */
public final class TodoFixRuns {

    private TodoFixRuns() {}

    /** True when {@code todo.lastFixRunId} names a run that is RUNNING or PENDING. */
    public static boolean isStillRunning(Todo todo, WorkflowRunRepository workflowRunRepository) {
        String runId = todo.getLastFixRunId();
        if (runId == null || runId.isBlank()) {
            return false;
        }
        return workflowRunRepository.findById(runId)
                .map(run -> run.getStatus() == RunStatus.RUNNING || run.getStatus() == RunStatus.PENDING)
                .orElse(false);
    }
}
