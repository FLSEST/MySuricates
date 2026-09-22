package com.automation.service.workflow;

/**
 * Published by {@link WorkflowService} when the workflows a project lists change: one is created,
 * renamed, gets other blocks or is deleted. Listened to by the onboarding file of the project,
 * which lists them (TODO 644fd5ad).
 */
public record ProjectWorkflowsChangedEvent(String projectId) {}
