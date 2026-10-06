package com.automation.config;

import com.automation.security.PublicPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "automation.security.enabled=true",
        "automation.security.api-key=test-api-key"
})
class SecurityConfigIntegrationTest {

    private static final String VALID_API_KEY = "test-api-key";
    private static final String API_KEY_HEADER = "X-API-Key";

    @Autowired
    private MockMvc mockMvc;

    // --- Group 1: Public endpoints accessible without auth ---

    @Test
    void publicEndpoint_health_accessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/system/health"))
                .andExpect(status().isOk());
    }

    @Test
    void publicEndpoint_info_accessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk());
    }

    @Test
    void publicEndpoint_authStatus_accessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/system/auth-status"))
                .andExpect(status().isOk());
    }

    @Test
    void publicEndpoint_actuatorHealth_accessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void publicEndpoint_jvmMemory_accessibleWithoutAuth() throws Exception {
        // c2cda5ed: the three heap numbers for the performance audit — without a key, but only those
        mockMvc.perform(get("/api/v1/system/jvm-memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jvm_memory_used_bytes").isNumber())
                .andExpect(jsonPath("$.jvm_memory_max_bytes").isNumber())
                .andExpect(jsonPath("$.jvm_heap_used_percent").isNumber());
    }

    @Test
    void publicEndpoint_webhookTrigger_accessibleWithoutAuth() throws Exception {
        mockMvc.perform(post("/api/v1/webhook/trigger/test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(not(403)));
    }

    @Test
    void publicEndpoint_websocket_accessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/ws/test"))
                .andExpect(status().is(not(403)));
    }

    @Test
    void publicEndpoint_oauthCallback_accessibleWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/integrations/github/callback"))
                .andExpect(status().is(not(403)));
    }

    // --- Group 2: Protected endpoints require auth ---

    @Test
    void protectedEndpoint_apiWithoutAuth_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedEndpoint_actuatorInfoWithoutAuth_returns403() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedEndpoint_actuatorMetricsWithoutAuth_returns403() throws Exception {
        // The metrics surface reveals routes, load profile and memory state — it stays
        // behind the key; only /api/v1/system/jvm-memory is open (c2cda5ed).
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/metrics/jvm.memory.used"))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedEndpoint_requestMetricsWithoutAuth_returns403() throws Exception {
        // The exception applies to that one path, not to its neighbours under /system.
        mockMvc.perform(get("/api/v1/system/request-metrics"))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedEndpoint_withInvalidApiKey_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/projects")
                        .header(API_KEY_HEADER, "wrong-key"))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedEndpoint_publicPathWithSuffix_returns403() throws Exception {
        // The public system paths are exact matches — a suffix does not make them open.
        mockMvc.perform(get("/api/v1/system/health/extra"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/system/info/extra"))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedEndpoint_integrationSnippet_withoutAuth_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/system/integration-snippet"))
                .andExpect(status().isForbidden());
    }

    @Test
    void nonApiPath_accessibleWithoutAuth() throws Exception {
        // anyRequest().permitAll(): die ausgelieferte Oberflaeche selbst braucht keinen Key.
        mockMvc.perform(get("/"))
                .andExpect(status().is(not(403)));
    }

    // --- Group 2b: path boundaries — an exception applies to the pattern, not to its neighbours ---

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/system/health/extra",
            "/api/v1/system/health/",
            "/api/v1/system/healthz",
            "/api/v1/system/jvm-memory/extra",
            "/api/v1/integrations/github/callback/extra",
            "/api/v1/integrations/a/b/callback",
            "/api/v1/integrations/github/callbackx",
            "/api/v1/integrations/callback",
            "/api/v1/webhook/triggerx",
            "/api/v1/webhook",
            "/api",
            "/actuator",
            "/actuator/health/liveness",
            "/actuator/healthz"
    })
    void pathBoundary_neighbourOfPublicPath_staysProtected(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/webhook/trigger",
            "/api/v1/webhook/trigger/a/b",
            "/ws",
            "/",
            "/index.html",
            "/assets/app.js",
            "/workflows/123"
    })
    void pathBoundary_publicPatternAndFrontend_reachableWithoutKey(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().is(not(403)));
    }

    // --- Group 2c: one list — filter chain and ApiKeyAuthFilter decide the same way ---

    /** One concrete path per pattern: {@code *} becomes one segment, {@code **} two. */
    private static String concrete(String pattern) {
        return pattern.replace("**", "a/b").replace("*", "x");
    }

    @Test
    void publicPaths_everyRuleReachableWithoutKey() throws Exception {
        List<String> paths = Stream.concat(PublicPaths.EXACT.stream(), PublicPaths.PATTERNS.stream())
                .map(SecurityConfigIntegrationTest::concrete).toList();
        assertThat(paths).hasSize(PublicPaths.EXACT.size() + PublicPaths.PATTERNS.size());
        for (String path : paths) {
            mockMvc.perform(get(path))
                    .andExpect(status().is(not(403)));
            assertThat(PublicPaths.requiresKey(new MockHttpServletRequest("GET", path)))
                    .as("Filter hält %s für geschützt, die Kette nicht", path).isFalse();
        }
    }

    @Test
    void publicPaths_protectedAreasNeedKey() throws Exception {
        for (String pattern : PublicPaths.PROTECTED) {
            String path = concrete(pattern);
            mockMvc.perform(get(path))
                    .andExpect(status().isForbidden());
            assertThat(PublicPaths.requiresKey(new MockHttpServletRequest("GET", path)))
                    .as("Filter hält %s für öffentlich, die Kette nicht", path).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/projects", "/api/v1/system/request-metrics", "/api/v1/system/health/extra",
            "/api/v1/system/health/", "/api/v1/integrations/github/callback", "/api/v1/integrations/a/b/callback",
            "/api/v1/webhook/trigger", "/api/v1/webhook/triggerx", "/api", "/actuator", "/actuator/health",
            "/actuator/health/liveness", "/actuator/metrics", "/ws/info", "/", "/index.html", "/workflows/123"
    })
    void publicPaths_filterAndChainAgree(String path) throws Exception {
        boolean forbidden = mockMvc.perform(get(path)).andReturn().getResponse().getStatus() == 403;
        assertThat(PublicPaths.requiresKey(new MockHttpServletRequest("GET", path)))
                .as("Filter-Protokoll und Filterkette für %s", path).isEqualTo(forbidden);
    }

    // --- Group 3: Protected endpoints with valid API key ---

    @Test
    void protectedEndpoint_apiWithValidKey_succeeds() throws Exception {
        mockMvc.perform(get("/api/v1/projects")
                        .header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpoint_actuatorMetricsWithValidKey_succeeds() throws Exception {
        // With a key the metrics surface stays reachable — only the way without a key is blocked.
        mockMvc.perform(get("/actuator/metrics/jvm.memory.used")
                        .header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void protectedEndpoint_integrationSnippet_withValidKey_succeeds() throws Exception {
        mockMvc.perform(get("/api/v1/system/integration-snippet")
                        .header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk());
    }

    // --- Group 4: CSRF disabled ---

    @Test
    void csrf_postWithoutCsrfToken_notRejected() throws Exception {
        mockMvc.perform(post("/api/v1/webhook/trigger/csrf-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(not(403)));
    }

    @Test
    void csrf_authenticatedPostWithoutCsrfToken_notRejected() throws Exception {
        // Writing calls behind the key also get through without a CSRF token — the key replaces it.
        mockMvc.perform(post("/api/v1/ws-ticket")
                        .header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk());
    }

    // --- Group 5: Stateless session ---

    @Test
    void sessionManagement_noSessionCookie() throws Exception {
        mockMvc.perform(get("/api/v1/system/health"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void sessionManagement_authenticatedRequest_createsNoSession() throws Exception {
        // STATELESS: even after a successful login via key no session is created
        // that a later call without a key could reuse.
        MvcResult result = mockMvc.perform(get("/api/v1/projects")
                        .header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
    }

    // --- Group 6: Security headers ---

    @Test
    void headers_cspHeaderIsSet() throws Exception {
        mockMvc.perform(get("/api/v1/system/health"))
                .andExpect(header().string("Content-Security-Policy",
                        "default-src 'self'; " +
                        "script-src 'self'; " +
                        "worker-src 'self' blob:; " +
                        "connect-src 'self' ws: wss:; " +
                        "style-src 'self' 'unsafe-inline'; " +
                        "font-src 'self' data:; " +
                        "img-src 'self' data:"))
                // no public CDN in any directive (script gadgets) — Monaco ships with the bundle
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("jsdelivr"))));
    }

    @Test
    void headers_xFrameOptionsDeny() throws Exception {
        mockMvc.perform(get("/api/v1/system/health"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void headers_cspAndFrameOptionsAlsoOnNonApiPaths() throws Exception {
        // Die Oberflaeche liegt ausserhalb von /api — dort zaehlt die CSP.
        mockMvc.perform(get("/"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.startsWith("default-src 'self'; script-src 'self';")))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void headers_cspAlsoOnRejectedRequests() throws Exception {
        mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isForbidden())
                .andExpect(header().exists("Content-Security-Policy"));
    }
}
