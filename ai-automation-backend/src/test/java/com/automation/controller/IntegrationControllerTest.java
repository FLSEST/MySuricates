package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.entity.IntegrationConnection;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.security.AllowedOrigins;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.integration.IntegrationAction;
import com.automation.service.integration.IntegrationOAuthService;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
import com.automation.service.integration.IntegrationVerification;
import com.automation.service.integration.OAuthConfig;
import com.automation.service.system.ApiKeyService;
import com.automation.service.system.OwnTailnetAddresses;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IntegrationController.class)
@Import({GlobalExceptionHandler.class, AllowedOrigins.class})
@AutoConfigureMockMvc(addFilters = false)
class IntegrationControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private IntegrationRegistry registry;
    @MockitoBean private IntegrationOAuthService oAuthService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private RateLimitConfig rateLimitConfig;
    @MockitoBean private OwnTailnetAddresses ownTailnet;

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
                .andExpect(jsonPath("$[0].actions[0].available").value(true))
                .andExpect(jsonPath("$[0].verification").value("SIMULATED"))
                .andExpect(jsonPath("$[0].verifiedAgainst").doesNotExist());
    }

    @Test
    void listProviders_namesWhatAVerifiedProviderWasCheckedAgainst() throws Exception {
        IntegrationProvider provider = provider("google-calendar");
        when(provider.getVerification()).thenReturn(IntegrationVerification.VERIFIED);
        when(provider.getVerifiedAgainst()).thenReturn("Google Calendar API");
        when(registry.getAllProviders()).thenReturn(List.of(provider));
        when(oAuthService.getConnection("google-calendar")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/integrations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].verification").value("VERIFIED"))
                .andExpect(jsonPath("$[0].verifiedAgainst").value("Google Calendar API"));
    }

    @Test
    void getAuthorizationUrl_usesRequestCallbackUrlAndScopes() throws Exception {
        when(oAuthService.getAuthorizationUrl(
                eq("google-calendar"),
                eq("http://localhost/api/v1/integrations/google-calendar/callback"),
                eq(List.of("calendar.write")), eq("http://localhost")))
                .thenReturn("https://auth.example/authorize");

        mockMvc.perform(get("/api/v1/integrations/google-calendar/connect")
                        .queryParam("scopes", "calendar.write"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").value("https://auth.example/authorize"));
    }

    @Test
    void handleCallback_returnsHtmlPageWithProviderName() throws Exception {
        when(oAuthService.getCallbackOrigin("state-1")).thenReturn("https://localhost:3000");
        when(oAuthService.handleCallback("code-1", "state-1",
                "http://localhost/api/v1/integrations/google-calendar/callback"))
                .thenReturn(IntegrationConnection.builder().providerId("google-calendar").build());

        mockMvc.perform(get("/api/v1/integrations/google-calendar/callback")
                        .param("code", "code-1")
                        .param("state", "state-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("google-calendar")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("oauth-callback")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-origin=\"https://localhost:3000\"")));
    }

    @Test
    void getAuthorizationUrl_bindsAllowedFrontendOriginToStateAndReportsCallbackOrigin() throws Exception {
        when(oAuthService.getAuthorizationUrl(eq("google-calendar"), any(), eq(null), eq("http://localhost:3000")))
                .thenReturn("https://auth.example/authorize");
        mockMvc.perform(get("/api/v1/integrations/google-calendar/connect")
                        .param("frontendOrigin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callbackOrigin").value("http://localhost"));
        verify(oAuthService).getAuthorizationUrl("google-calendar",
                "http://localhost/api/v1/integrations/google-calendar/callback", null, "http://localhost:3000");
    }

    @Test
    void getAuthorizationUrl_rejectsUntrustedFrontendOrigin() throws Exception {
        mockMvc.perform(get("/api/v1/integrations/google-calendar/connect")
                        .param("frontendOrigin", "https://evil.example"))
                .andExpect(status().isBadRequest());
        org.mockito.Mockito.verifyNoInteractions(oAuthService);
    }

    @Test
    void getAuthorizationUrl_acceptsThisInstancesOwnTailscaleName_butNoForeignTailnet() throws Exception {
        // a8056256: same answer as CORS — the own MagicDNS name yes, a foreign tailnet no
        when(ownTailnet.hosts()).thenReturn(Set.of("mine.tail1234.ts.net"));
        when(oAuthService.getAuthorizationUrl(eq("google-calendar"), any(), eq(null),
                eq("https://mine.tail1234.ts.net:3000"))).thenReturn("https://auth.example/authorize");

        mockMvc.perform(get("/api/v1/integrations/google-calendar/connect")
                        .param("frontendOrigin", "https://mine.tail1234.ts.net:3000"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/integrations/google-calendar/connect")
                        .param("frontendOrigin", "https://evil.tail0000.ts.net:3000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void saveCredentials_withMissingSecretReturnsBadRequest() throws Exception {
        IntegrationProvider provider = provider("google-calendar");
        when(provider.requiresCredentials()).thenReturn(true);
        when(registry.getProvider("google-calendar")).thenReturn(provider);

        mockMvc.perform(post("/api/v1/integrations/google-calendar/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId":"client-only"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void saveCredentials_allowsEmpty_whenProviderDoesNotRequireCredentials() throws Exception {
        IntegrationProvider provider = provider("taskwarrior");
        when(provider.requiresCredentials()).thenReturn(false);
        when(registry.getProvider("taskwarrior")).thenReturn(provider);
        when(oAuthService.getConnection("taskwarrior")).thenReturn(Optional.empty());
        when(oAuthService.isProviderConfigured("taskwarrior")).thenReturn(true);

        mockMvc.perform(post("/api/v1/integrations/taskwarrior/credentials")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId":"","clientSecret":""}
                                """))
                .andExpect(status().isOk());

        verify(oAuthService).saveCredentials("taskwarrior", "", "");
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

    @Test
    void handleCallback_returnsError_whenStateInvalid() throws Exception {
        when(oAuthService.handleCallback(eq("code-x"), eq("bad-state"), any()))
                .thenThrow(new IllegalArgumentException("Invalid or expired OAuth state"));

        mockMvc.perform(get("/api/v1/integrations/google-calendar/callback")
                        .param("code", "code-x")
                        .param("state", "bad-state"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void disconnect_returnsNoContent_whenProviderConnected() throws Exception {
        mockMvc.perform(delete("/api/v1/integrations/google-calendar/connection"))
                .andExpect(status().isNoContent());

        verify(oAuthService).disconnect("google-calendar");
    }

    @Test
    void testAction_returns400_whenProviderUnknown() throws Exception {
        when(registry.getProvider("unknown"))
                .thenThrow(new IllegalArgumentException("Unknown integration provider: unknown"));

        mockMvc.perform(post("/api/v1/integrations/unknown/actions/list_events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void testAction_returns409_whenCredentialsMissing() throws Exception {
        IntegrationProvider provider = provider("google-calendar");
        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(oAuthService.getValidAccessToken("google-calendar"))
                .thenThrow(new IllegalStateException("No connection for provider: google-calendar"));

        mockMvc.perform(post("/api/v1/integrations/google-calendar/actions/list_events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void getActions_returnsAvailableActions_forProvider() throws Exception {
        IntegrationProvider provider = provider("google-calendar");
        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(oAuthService.getConnection("google-calendar")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/integrations/google-calendar/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("list_events"))
                .andExpect(jsonPath("$[0].description").value("List events"));
    }

    private IntegrationProvider provider(String providerId) {
        IntegrationProvider provider = org.mockito.Mockito.mock(IntegrationProvider.class);
        when(provider.getProviderId()).thenReturn(providerId);
        when(provider.getDisplayName()).thenReturn("Google Calendar");
        when(provider.getIcon()).thenReturn("Calendar");
        when(provider.getDescription()).thenReturn("Calendar sync");
        when(provider.getVerification()).thenReturn(IntegrationVerification.SIMULATED);
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

    // ── isActionAvailable (PAT scopes, TODO 62705e0b) ──────────────────────────────

    private static com.automation.service.integration.IntegrationAction action(java.util.List<String> required) {
        return new com.automation.service.integration.IntegrationAction("create_issue", "desc", java.util.Map.of(), required);
    }

    @Test
    void isActionAvailable_noRequiredScopes_alwaysAvailable() {
        assertThat(IntegrationController.isActionAvailable(action(java.util.List.of()), null)).isTrue();
        assertThat(IntegrationController.isActionAvailable(action(java.util.List.of()), "")).isTrue();
    }

    @Test
    void isActionAvailable_unknownScopes_isNotReportedAsMissing() {
        // fine-grained PAT or provider without scope reporting: we cannot prove a gap
        assertThat(IntegrationController.isActionAvailable(action(java.util.List.of("repo")), null)).isTrue();
    }

    @Test
    void isActionAvailable_grantedScopesCoverRequirement() {
        assertThat(IntegrationController.isActionAvailable(action(java.util.List.of("repo")), "repo read:org")).isTrue();
    }

    @Test
    void isActionAvailable_knownScopesWithoutRequirement_isFlagged() {
        assertThat(IntegrationController.isActionAvailable(action(java.util.List.of("repo")), "public_repo read:org")).isFalse();
        assertThat(IntegrationController.isActionAvailable(action(java.util.List.of("repo")), "")).isFalse();
    }
}
