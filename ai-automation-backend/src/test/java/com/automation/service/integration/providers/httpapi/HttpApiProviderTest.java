package com.automation.service.integration.providers.httpapi;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HttpApiProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpApiClient client = mock(HttpApiClient.class);

    private static ModuleRegistry modules(boolean httpApi) {
        return ModuleRegistry.of(Map.of("integrations", "true", "http-api", String.valueOf(httpApi), "ui-editable", "true"), Map.of());
    }

    private HttpApiProvider provider(boolean httpApi) {
        return new HttpApiProvider(client, JSON, modules(httpApi));
    }

    @Test
    void moduleOff_executeActionIsRefused_withoutAnyRequest() {
        assertThatThrownBy(() -> provider(false).executeAction("request", Map.of(), "https://api.example.com|"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("module 'http-api'");
        verify(client, never()).send(any(), any());
    }

    @Test
    void moduleOff_theRegistryHidesTheProvider_andTurningItOnShowsItAgain() {
        ModuleRegistry registryModules = modules(false);
        HttpApiProvider provider = new HttpApiProvider(client, JSON, registryModules);
        IntegrationProvider other = mock(IntegrationProvider.class);
        when(other.getProviderId()).thenReturn("github");
        IntegrationRegistry registry = new IntegrationRegistry(List.of(provider, other), registryModules);

        assertThat(registry.getAllProviders()).extracting(IntegrationProvider::getProviderId).containsExactly("github");
        assertThat(registry.hasProvider("http-api")).isFalse();
        assertThatThrownBy(() -> registry.getProvider("http-api"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("module 'http-api'");

        registryModules.applyUiOverrides(Map.of(ModuleCatalog.HTTP_API, true));
        assertThat(registry.hasProvider("http-api")).isTrue();
        assertThat(registry.getProvider("http-api")).isSameAs(provider);
    }

    @Test
    void presetsLeaveTheModuleOffExceptFull() {
        assertThat(ModuleRegistry.of(Map.of("preset", "core"), Map.of()).isEnabled(ModuleCatalog.HTTP_API)).isFalse();
        assertThat(ModuleRegistry.of(Map.of("preset", "research"), Map.of()).isEnabled(ModuleCatalog.HTTP_API)).isFalse();
        assertThat(ModuleRegistry.of(Map.of("preset", "full"), Map.of()).isEnabled(ModuleCatalog.HTTP_API)).isTrue();
        assertThat(ModuleCatalog.HTTP_API.requires()).containsExactly(ModuleCatalog.INTEGRATIONS);
    }

    @Test
    void moduleOn_requestIsBuiltAndSent() {
        when(client.send(any(), any())).thenReturn(Map.of("status", 200));

        Object result = provider(true).executeAction("request", Map.of("path", "users"),
                "https://api.example.com/v1|Authorization: Bearer x");

        assertThat(result).isEqualTo(Map.of("status", 200));
        verify(client).send(any(HttpApiTarget.class), any(HttpApiRequest.class));
    }

    @Test
    void unknownActionAndMissingConnectionAreRefused() {
        HttpApiProvider provider = provider(true);
        assertThatThrownBy(() -> provider.executeAction("delete_everything", Map.of(), "https://api.example.com|"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.executeAction("request", Map.of(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No http-api connection configured");
    }

    @Test
    void credentialsAreCheckedOnSave_andTheAccountIsHostPlusPath() {
        HttpApiProvider provider = provider(true);
        provider.validateCredentialInput("https://api.example.com/v1", "X-Api-Key: abc");
        assertThatThrownBy(() -> provider.validateCredentialInput("ftp://x", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.validateCredentialInput("https://x", "X-Api-Key: a\r\nb"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(provider.fetchAccountInfo("https://api.example.com/v1|X-Api-Key: abc"))
                .isEqualTo("{\"name\":\"api.example.com/v1\"}");
        assertThat(provider.requiresOAuthFlow()).isFalse();
        assertThat(provider.requiresCredentials()).isFalse();
        assertThat(provider.requiredModule()).isEqualTo(ModuleCatalog.HTTP_API);
        assertThat(provider.getAvailableActions()).extracting(a -> a.name()).containsExactly("request");
    }

    @Test
    void metadata_identifiesTheProvider() {
        HttpApiProvider provider = provider(true);
        assertThat(provider.getProviderId()).isEqualTo("http-api");
        assertThat(provider.getDisplayName()).isEqualTo("HTTP API");
        assertThat(provider.getIcon()).isNotBlank();
        assertThat(provider.getDescription()).isNotBlank();
    }

    @Test
    void oauthConfig_isEmptyAndUsesNoRealEnvVariables() {
        var config = provider(true).getOAuthConfig();
        assertThat(config.authorizationUrl()).isEmpty();
        assertThat(config.tokenUrl()).isEmpty();
        assertThat(config.defaultScopes()).isEmpty();
        assertThat(config.clientIdEnvVar()).contains("NO_ENV");
        assertThat(config.clientSecretEnvVar()).contains("NO_ENV");
    }

    @Test
    void requestAction_declaresMethodsAndOptionalParameters() {
        var action = provider(true).getAvailableActions().get(0);
        assertThat(action.name()).isEqualTo("request");
        assertThat(action.description()).isNotBlank();
        assertThat(action.inputSchema().keySet())
                .containsExactlyInAnyOrder("method", "path", "query", "headers", "body", "timeout_seconds", "fail_on_error");
        assertThat(((Map<?, ?>) action.inputSchema().get("method")).get("enum"))
                .isEqualTo(List.copyOf(HttpApiRequest.METHODS));
    }

    @Test
    void fetchAccountInfo_withoutConnectionFails_andSendsNothing() {
        assertThatThrownBy(() -> provider(true).fetchAccountInfo(null))
                .isInstanceOf(IllegalStateException.class);
        verify(client, never()).send(any(), any());
    }

    @Test
    void validateCredentialInput_acceptsEmptyAuthHeader() {
        provider(true).validateCredentialInput("https://api.example.com", "");
        assertThatThrownBy(() -> provider(true).validateCredentialInput("", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
