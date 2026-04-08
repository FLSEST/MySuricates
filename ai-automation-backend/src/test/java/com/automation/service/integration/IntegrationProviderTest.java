package com.automation.service.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class IntegrationProviderTest {

    private IntegrationProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DummyProvider();
    }

    // --- Default method tests ---

    @Test
    void should_returnNull_when_fetchAccountInfoCalledWithDefaultImplementation() {
        assertThat(provider.fetchAccountInfo("any-token")).isNull();
    }

    @Test
    void should_returnNull_when_fetchAccountInfoCalledWithNullToken() {
        assertThat(provider.fetchAccountInfo(null)).isNull();
    }

    @Test
    void should_returnNull_when_fetchAccountInfoCalledWithEmptyToken() {
        assertThat(provider.fetchAccountInfo("")).isNull();
    }

    @Test
    void should_returnTrue_when_requiresOAuthFlowCalledWithDefaultImplementation() {
        assertThat(provider.requiresOAuthFlow()).isTrue();
    }

    // --- Dummy implementation contract tests ---

    @Test
    void should_returnProviderId_when_getProviderIdCalled() {
        assertThat(provider.getProviderId()).isEqualTo("dummy");
    }

    @Test
    void should_returnDisplayName_when_getDisplayNameCalled() {
        assertThat(provider.getDisplayName()).isEqualTo("Dummy Provider");
    }

    @Test
    void should_returnIcon_when_getIconCalled() {
        assertThat(provider.getIcon()).isEqualTo("plug");
    }

    @Test
    void should_returnDescription_when_getDescriptionCalled() {
        assertThat(provider.getDescription()).isNotBlank();
    }

    @Test
    void should_returnOAuthConfig_when_getOAuthConfigCalled() {
        OAuthConfig config = provider.getOAuthConfig();
        assertThat(config).isNotNull();
        assertThat(config.authorizationUrl()).isNotBlank();
        assertThat(config.tokenUrl()).isNotBlank();
        assertThat(config.defaultScopes()).isNotNull();
        assertThat(config.clientIdEnvVar()).isNotBlank();
        assertThat(config.clientSecretEnvVar()).isNotBlank();
    }

    @Test
    void should_returnActions_when_getAvailableActionsCalled() {
        List<IntegrationAction> actions = provider.getAvailableActions();
        assertThat(actions).isNotNull().hasSize(1);
        assertThat(actions.get(0).name()).isEqualTo("test_action");
        assertThat(actions.get(0).description()).isNotBlank();
        assertThat(actions.get(0).inputSchema()).isNotNull();
    }

    @Test
    void should_returnResult_when_executeActionCalledWithValidAction() {
        Object result = provider.executeAction("test_action", Map.of("key", "value"), "token");
        assertThat(result).isNotNull().isEqualTo("executed:test_action");
    }

    // --- Override default methods tests ---

    @Test
    void should_returnCustomAccountInfo_when_defaultMethodOverridden() {
        IntegrationProvider customProvider = new DummyProviderWithOverrides();
        assertThat(customProvider.fetchAccountInfo("token-123")).isEqualTo("user@example.com");
    }

    @Test
    void should_returnFalse_when_requiresOAuthFlowOverriddenToFalse() {
        IntegrationProvider customProvider = new DummyProviderWithOverrides();
        assertThat(customProvider.requiresOAuthFlow()).isFalse();
    }

    // --- Dummy implementation (minimal, used only in this test) ---

    private static class DummyProvider implements IntegrationProvider {
        @Override
        public String getProviderId() {
            return "dummy";
        }

        @Override
        public String getDisplayName() {
            return "Dummy Provider";
        }

        @Override
        public String getIcon() {
            return "plug";
        }

        @Override
        public String getDescription() {
            return "A dummy provider for testing";
        }

        @Override
        public OAuthConfig getOAuthConfig() {
            return new OAuthConfig(
                    "https://auth.example.com/authorize",
                    "https://auth.example.com/token",
                    List.of("read", "write"),
                    "DUMMY_CLIENT_ID",
                    "DUMMY_CLIENT_SECRET"
            );
        }

        @Override
        public List<IntegrationAction> getAvailableActions() {
            return List.of(new IntegrationAction(
                    "test_action",
                    "A test action",
                    Map.of("type", "object")
            ));
        }

        @Override
        public Object executeAction(String action, Map<String, Object> params, String accessToken) {
            return "executed:" + action;
        }
    }

    private static class DummyProviderWithOverrides extends DummyProvider {
        @Override
        public String fetchAccountInfo(String accessToken) {
            return "user@example.com";
        }

        @Override
        public boolean requiresOAuthFlow() {
            return false;
        }
    }
}
