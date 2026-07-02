package com.automation.service.integration;

import java.util.List;

/**
 * Capability interface for integration providers that can receive a MySuricates TODO
 * as a task in their external system (GitHub issue, Jira issue, Trello card, …).
 *
 * <p>This is the integration-agnostic contract behind "export a single TODO to a tracker":
 * discovery is done via {@code instanceof TaskExportCapable} (no hardcoded provider list,
 * no flag to forget), and the compiler enforces the uniform input/output shape so a new
 * provider cannot be wired in half-way.
 *
 * <p>A provider that implements this interface should also be a normal
 * {@link IntegrationProvider} {@code @Component}; it is then automatically offered as a
 * task target wherever a connected task-capable integration is required.
 */
public interface TaskExportCapable {

    /**
     * Create an item in the external system from a TODO.
     *
     * @param input       uniform task fields ({@code target} is provider-specific:
     *                    repo for GitHub, projectKey for Jira, listId for Trello)
     * @param accessToken the provider credential as returned by
     *                    {@code IntegrationOAuthService.getValidAccessToken(providerId)}
     * @return a uniform reference to the created item; its {@code referenceType}/{@code referenceId}
     *         are stored on the TODO for correlation and loop-protection
     */
    TaskRef createTask(TaskExportInput input, String accessToken);

    /** Uniform, provider-agnostic task fields. */
    record TaskExportInput(String title, String body, List<String> labels, String target) {}

    /** Uniform reference to the created external item. */
    record TaskRef(String referenceType, String referenceId, String url) {}
}
