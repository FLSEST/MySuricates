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
     * Short form without {@code agentSessionRef} — for all callers that have no agent session
     * (workflow blocks, the assistant, test fixtures). The field came with TODO 3497533c;
     * an additional constructor is the more honest solution here than extending forty
     * positional calls by a {@code null} that they are not interested in.
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
