package com.automation.dto.projection;

/**
 * Filter und Ziel-Workflow eines aktiven TodoTriggers (TODO c6ea8f5c) – ohne den Ziel-Workflow zu
 * laden, der ueber {@code TodoTrigger.targetWorkflow} (EAGER) samt Definition mitkaeme.
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
