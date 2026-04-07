package com.automation.service.integration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class IntegrationRegistryTest {

    private IntegrationProvider mockProvider(String id) {
        return new IntegrationProvider() {
            @Override public String getProviderId() { return id; }
            @Override public String getDisplayName() { return id; }
            @Override public String getIcon() { return "Plug"; }
            @Override public String getDescription() { return "Test"; }
            @Override public OAuthConfig getOAuthConfig() { return new OAuthConfig("", "", List.of(), "", ""); }
            @Override public List<IntegrationAction> getAvailableActions() { return List.of(); }
            @Override public Object executeAction(String action, Map<String, Object> params, String accessToken) { return null; }
        };
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
}
