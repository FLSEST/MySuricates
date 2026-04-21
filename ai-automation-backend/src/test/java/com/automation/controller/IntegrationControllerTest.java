package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.entity.IntegrationConnection;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationOAuthService;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
import com.automation.service.integration.OAuthConfig;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IntegrationController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class IntegrationControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private IntegrationRegistry registry;
    @MockitoBean private IntegrationOAuthService oAuthService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    @Test
    void listProviders_returnsConnectionMetadataOptionalScopesAndActions() throws Exception {
        IntegrationProvider provider = provider("google-calendar");
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("google-calendar")
                .status(IntegrationConnection.ConnectionStatus.CONNECTED)
                .accountInfo("{\"email\":\"user@example.com\",\"name\":\"User Example\"}")
                .scopes("calendar.read calendar.write")
                .build();

        when(registry.getAllProviders()).thenReturn(List.of(provider));
        when(oAuthService.getConnection("google-calendar")).thenReturn(Optional.of(connection));
        when(oAuthService.isProviderConfigured("google-calendar")).thenReturn(true);

        mockMvc.perform(get("/api/v1/integrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].providerId").value("google-calendar"))
                .andExpect(jsonPath("$[0].status").value("CONNECTED"))
                .andExpect(jsonPath("$[0].configured").value(true))
                .andExpect(jsonPath("$[0].accountEmail").value("user@example.com"))
                .andExpect(jsonPath("$[0].accountName").value("User Example"))
                .andExpect(jsonPath("$[0].optionalScopes[0].scope").value("calendar.write"))
                .andExpect(jsonPath("$[0].optionalScopes[0].granted").value(true))
                .andExpect(jsonPath("$[0].actions[0].name").value("list_events"))
                .andExpect(jsonPath("$[0].actions[0].available").value(true));
    }

    @Test
    void getAuthorizationUrl_usesRequestCallbackUrlAndScopes() throws Exception {
        when(oAuthService.getAuthorizationUrl(
                eq("google-calendar"),
                eq("http://localhost/api/v1/integrations/google-calendar/callback"),
                eq(List.of("calendar.write"))))
                .thenReturn("https://auth.example/authorize");

        mockMvc.perform(get("/api/v1/integrations/google-calendar/connect")
                        .queryParam("scopes", "calendar.write"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").value("https://auth.example/authorize"));
    }

    @Test
    void handleCallback_returnsHtmlPageWithProviderName() throws Exception {
        when(oAuthService.handleCallback("code-1", "state-1",
                "http://localhost/api/v1/integrations/google-calendar/callback"))
                .thenReturn(IntegrationConnection.builder().providerId("google-calendar").build());

        mockMvc.perform(get("/api/v1/integrations/google-calendar/callback")
                        .param("code", "code-1")
                        .param("state", "state-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("google-calendar")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("oauth-callback")));
    }

    @Test
    void saveCredentials_withMissingSecretReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/integrations/google-calendar/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId":"client-only"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void testAction_executesProviderActionWithResolvedAccessToken() throws Exception {
        IntegrationProvider provider = provider("google-calendar");
        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(oAuthService.getValidAccessToken("google-calendar")).thenReturn("access-token");
        when(provider.executeAction("list_events", Map.of("limit", 3), "access-token"))
                .thenReturn(Map.of("count", 3));

        mockMvc.perform(post("/api/v1/integrations/google-calendar/actions/list_events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"limit":3}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3));
    }

    @Test
    void saveCredentials_withValidBodyDelegatesToOAuthService() throws Exception {
        IntegrationProvider provider = provider("google-calendar");
        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(oAuthService.getConnection("google-calendar")).thenReturn(Optional.empty());
        when(oAuthService.isProviderConfigured("google-calendar")).thenReturn(false);

        mockMvc.perform(post("/api/v1/integrations/google-calendar/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId":"client-1","clientSecret":"secret-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value("google-calendar"));

        verify(oAuthService).saveCredentials("google-calendar", "client-1", "secret-1");
    }

    private IntegrationProvider provider(String providerId) {
        IntegrationProvider provider = org.mockito.Mockito.mock(IntegrationProvider.class);
        when(provider.getProviderId()).thenReturn(providerId);
        when(provider.getDisplayName()).thenReturn("Google Calendar");
        when(provider.getIcon()).thenReturn("Calendar");
        when(provider.getDescription()).thenReturn("Calendar sync");
        when(provider.getOAuthConfig()).thenReturn(new OAuthConfig(
                "https://auth.example/authorize",
                "https://auth.example/token",
                List.of("calendar.read"),
                List.of("calendar.write"),
                "GOOGLE_CLIENT_ID",
                "GOOGLE_CLIENT_SECRET",
                Map.of()
        ));
        when(provider.getAvailableActions()).thenReturn(List.of(
                new IntegrationAction("list_events", "List events", Map.of(), List.of("calendar.read"))
        ));
        return provider;
    }
}
