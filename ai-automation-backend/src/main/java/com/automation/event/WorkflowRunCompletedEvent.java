package com.automation.event;

import com.automation.entity.RunStatus;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.Map;

@Getter
public class WorkflowRunCompletedEvent extends ApplicationEvent {

    private final String runId;
    private final String workflowId;
    private final String workflowName;
    private final String projectId;
    private final String projectPath;
    private final RunStatus status;
    private final Map<String, Object> runMetadata;
    private final Map<String, Object> workflowMetadata;
    private final Map<String, Object> error;

    public WorkflowRunCompletedEvent(Object source, String runId, String workflowId, String workflowName,
                                     String projectId, String projectPath, RunStatus status,
                                     Map<String, Object> runMetadata, Map<String, Object> workflowMetadata,
                                     Map<String, Object> error) {
        super(source);
        this.runId = runId;
        this.workflowId = workflowId;
        this.workflowName = workflowName;
        this.projectId = projectId;
        this.projectPath = projectPath;
        this.status = status;
        this.runMetadata = runMetadata != null ? runMetadata : Map.of();
        this.workflowMetadata = workflowMetadata != null ? workflowMetadata : Map.of();
        this.error = error != null ? error : Map.of();
    }

    @SuppressWarnings("unchecked")
    public boolean isAutoTodoEnabled() {
        Object autoTodo = workflowMetadata.get("autoTodo");
        if (autoTodo instanceof Map<?, ?> map) {
            Object enabled = map.get("enabled");
            return Boolean.TRUE.equals(enabled);
        }
        return false;
    }
}
