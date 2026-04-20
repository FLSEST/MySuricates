package com.automation.service.workflow;

/**
 * Published by {@link WorkflowService} whenever a workflow's identifying content
 * (name, description, blocks) is created or changes. Listened to by e.g. the
 * tagging service to refresh auto-generated tags.
 */
public record WorkflowContentChangedEvent(String workflowId) {}
