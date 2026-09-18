package com.automation.dto.response;

import com.automation.entity.Todo;

import java.time.Instant;
import java.util.List;

public record TodoResponse(
        String id,
        String title,
        String description,
        Todo.Status status,
        Todo.Priority priority,
        List<String> tags,
        String projectId,
        String projectName,
        String linkedWorkflowId,
        String linkedWorkflowName,
        Todo.Source source,
        String sourceAgent,
        String sessionId,
        String agentSessionRef,
        String workflowRunId,
        String referenceType,
        String referenceId,
        String exportedProvider,
        String exportedRef,
        String exportedUrl,
        int fixAttempts,
        String lastFixRunId,
        Todo.ResolutionState resolutionState,
        Instant dueDate,
        Instant createdAt,
        Instant updatedAt
) {
    /** Kurzform ohne {@code agentSessionRef} (TODO 3497533c) — fuer Test-Fixturen. */
    public TodoResponse(String id, String title, String description, Todo.Status status,
                        Todo.Priority priority, List<String> tags, String projectId,
                        String projectName, String linkedWorkflowId, String linkedWorkflowName,
                        Todo.Source source, String sourceAgent, String sessionId,
                        String workflowRunId, String referenceType, String referenceId,
                        String exportedProvider, String exportedRef, String exportedUrl,
                        int fixAttempts, String lastFixRunId, Todo.ResolutionState resolutionState,
                        Instant dueDate, Instant createdAt, Instant updatedAt) {
        this(id, title, description, status, priority, tags, projectId, projectName,
                linkedWorkflowId, linkedWorkflowName, source, sourceAgent, sessionId, null,
                workflowRunId, referenceType, referenceId, exportedProvider, exportedRef,
                exportedUrl, fixAttempts, lastFixRunId, resolutionState, dueDate, createdAt,
                updatedAt);
    }
}
