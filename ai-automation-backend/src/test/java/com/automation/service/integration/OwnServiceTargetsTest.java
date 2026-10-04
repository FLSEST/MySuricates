package com.automation.service.integration;

import com.automation.service.integration.providers.caldav.CalDavCredentials;
import com.automation.service.integration.providers.caldav.CalDavHttpClient;
import com.automation.service.integration.providers.gitea.GiteaHttpClient;
import com.automation.service.integration.providers.gitlab.GitLabHttpClient;
import com.automation.service.integration.providers.ntfy.NtfyHttpClient;
import com.automation.testsupport.TestTargetGuards;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The providers that talk to a self-hosted service declare {@link OutboundTarget#ownService()}: with
 * {@code private-network-guard-own-services} on, a local target is refused before any request, with
 * the guard's own message (not wrapped into a transport error). That they reach local services with
 * the switch off (the default) is shown by their own tests against a loopback server.
 */
class OwnServiceTargetsTest {

    /** Port 1 on loopback: nothing listens there, so only the guard can produce the expected error. */
    private static final String LOCAL = "http://127.0.0.1:1";

    @Test
    void ownServiceProvidersAreRefusedLocallyWithTheSwitchOn() {
        OutboundHttp guarded = TestTargetGuards.outbound(TestTargetGuards.on());
        ObjectMapper json = new ObjectMapper();
        Map<String, ThrowingCallable> calls = new LinkedHashMap<>();
        calls.put("gitea", () -> new GiteaHttpClient(json, guarded).get(LOCAL + "/api/v1/user", "t", Object.class));
        calls.put("gitlab", () -> new GitLabHttpClient(json, guarded).get(LOCAL + "/api/v4/user", "t", Object.class));
        calls.put("ntfy", () -> new NtfyHttpClient(json, guarded).get(LOCAL + "/v1/health", null));
        calls.put("caldav", () -> new CalDavHttpClient(guarded)
                .get(CalDavCredentials.parse(LOCAL + "/dav|alex:geheim"), URI.create(LOCAL + "/dav/cal.ics")));

        calls.forEach((provider, call) -> assertThatThrownBy(call)
                .as(provider)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("local or private network address")
                .hasMessageContaining("automation.hardening.private-network-guard-own-services=false")
                .hasMessageNotContaining("geheim"));
    }
}
