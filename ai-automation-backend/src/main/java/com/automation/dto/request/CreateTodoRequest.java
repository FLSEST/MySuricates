package com.automation.dto.request;

import com.automation.entity.Todo;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateTodoRequest(
        @NotBlank String title,
        String description,
        Todo.Status status,
        Todo.Priority priority,
        List<String> tags,
        String projectId,
        String linkedWorkflowId,
        Todo.Source source,
        String sourceAgent,
        String sessionId,
        /** UUID hinter `claude --resume` — die Konversation, nicht die Shell-Session. */
        String agentSessionRef,
        String workflowRunId,
        String referenceType,
        String referenceId,
        String dueDate
) {
    /**
     * Kurzform ohne {@code agentSessionRef} — fuer alle Aufrufer, die keine Agenten-Sitzung
     * haben (Workflow-Bloecke, der Assistent, Test-Fixturen). Das Feld kam mit TODO 3497533c
     * hinzu; ein zusaetzlicher Konstruktor ist hier die ehrlichere Loesung, als vierzig
     * positionsgebundene Aufrufe um ein {@code null} zu erweitern, das sie nicht interessiert.
     */
    public CreateTodoRequest(String title, String description, Todo.Status status,
                             Todo.Priority priority, List<String> tags, String projectId,
                             String linkedWorkflowId, Todo.Source source, String sourceAgent,
                             String sessionId, String workflowRunId, String referenceType,
                             String referenceId, String dueDate) {
        this(title, description, status, priority, tags, projectId, linkedWorkflowId, source,
                sourceAgent, sessionId, null, workflowRunId, referenceType, referenceId, dueDate);
    }
}
