package com.automation.dto.projection;

/**
 * Filter and target workflow of an active TodoTrigger (TODO c6ea8f5c) – without loading the target workflow,
 * which would come along with its definition via {@code TodoTrigger.targetWorkflow} (EAGER).
 */
public record TodoTriggerMatchRow(
        String id,
        String tagPattern,
        String projectId,
        String sourceFilter,
        String minPriority,
        String targetWorkflowId,
        String description
) {}
