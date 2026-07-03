package com.automation.service.integration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class IntegrationRegistryTest {

    private IntegrationProvider mockProvider(String id) {
        return mockProvider(id, id);
    }

    private IntegrationProvider mockProvider(String id, String displayName) {
        return new IntegrationProvider() {
            @Override public String getProviderId() { return id; }
            @Override public String getDisplayName() { return displayName; }
            @Override public String getIcon() { return "Plug"; }
            @Override public String getDescription() { return "Test"; }
            @Override public OAuthConfig getOAuthConfig() { return new OAuthConfig("", "", List.of(), "", ""); }
            @Override public List<IntegrationAction> getAvailableActions() { return List.of(); }
            @Override public Object executeAction(String action, Map<String, Object> params, String accessToken) { return null; }
        };
    }

    /** Provider that additionally implements TaskExportCapable (like GitHubProvider). */
    private IntegrationProvider mockTaskExportProvider(String id) {
        class TaskExportProvider implements IntegrationProvider, TaskExportCapable {
            @Override public String getProviderId() { return id; }
            @Override public String getDisplayName() { return id; }
            @Override public String getIcon() { return "Plug"; }
            @Override public String getDescription() { return "Test export"; }
            @Override public OAuthConfig getOAuthConfig() { return new OAuthConfig("", "", List.of(), "", ""); }
            @Override public List<IntegrationAction> getAvailableActions() { return List.of(); }
            @Override public Object executeAction(String action, Map<String, Object> params, String accessToken) { return null; }
            @Override public TaskRef createTask(TaskExportInput input, String accessToken) { return new TaskRef("issue", "1", "http://example.org/1"); }
        }
        return new TaskExportProvider();
    }

    @Test
    void registersProvidersFromList() {
        IntegrationRegistry registry = new IntegrationRegistry(
                List.of(mockProvider("google-calendar"), mockProvider("slack"))
        );

        assertThat(registry.getAllProviders()).hasSize(2);
        assertThat(registry.hasProvider("google-calendar")).isTrue();
        assertThat(registry.hasProvider("slack")).isTrue();
    }

    @Test
    void getProviderReturnsCorrectProvider() {
        IntegrationRegistry registry = new IntegrationRegistry(List.of(mockProvider("google-calendar")));
        IntegrationProvider provider = registry.getProvider("google-calendar");
        assertThat(provider.getProviderId()).isEqualTo("google-calendar");
    }

    @Test
    void getProviderThrowsForUnknown() {
        IntegrationRegistry registry = new IntegrationRegistry(List.of());
        assertThatThrownBy(() -> registry.getProvider("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void hasProviderReturnsFalseForUnknown() {
        IntegrationRegistry registry = new IntegrationRegistry(List.of());
        assertThat(registry.hasProvider("nonexistent")).isFalse();
    }

    @Test
    void getAllProvidersReturnsEmptyForEmptyRegistry() {
        IntegrationRegistry registry = new IntegrationRegistry(List.of());
        assertThat(registry.getAllProviders()).isEmpty();
    }

    @Test
    void getAllProvidersContainsAllRegisteredIds() {
        IntegrationRegistry registry = new IntegrationRegistry(
                List.of(mockProvider("github"), mockProvider("jira"), mockProvider("slack"))
        );

        assertThat(registry.getAllProviders())
                .extracting(IntegrationProvider::getProviderId)
                .containsExactlyInAnyOrder("github", "jira", "slack");
    }

    @Test
    void lastRegisteredProviderWinsForDuplicateId() {
        IntegrationRegistry registry = new IntegrationRegistry(
                List.of(mockProvider("github", "first"), mockProvider("github", "second"))
        );

        assertThat(registry.getAllProviders()).hasSize(1);
        assertThat(registry.getProvider("github").getDisplayName()).isEqualTo("second");
    }

    @Test
    void getTaskExportProvidersReturnsOnlyTaskExportCapable() {
        IntegrationProvider plain = mockProvider("google-calendar");
        IntegrationProvider exportCapable = mockTaskExportProvider("github");
        IntegrationRegistry registry = new IntegrationRegistry(List.of(plain, exportCapable));

        List<IntegrationProvider> exporters = registry.getTaskExportProviders();

        assertThat(exporters).hasSize(1);
        assertThat(exporters.get(0).getProviderId()).isEqualTo("github");
        assertThat(exporters.get(0)).isInstanceOf(TaskExportCapable.class);
    }

    @Test
    void getTaskExportProvidersReturnsEmptyWhenNoneCapable() {
        IntegrationRegistry registry = new IntegrationRegistry(
                List.of(mockProvider("google-calendar"), mockProvider("slack"))
        );

        assertThat(registry.getTaskExportProviders()).isEmpty();
    }

    @Test
    void getTaskExportProvidersReturnsEmptyForEmptyRegistry() {
        IntegrationRegistry registry = new IntegrationRegistry(List.of());
        assertThat(registry.getTaskExportProviders()).isEmpty();
    }
}
