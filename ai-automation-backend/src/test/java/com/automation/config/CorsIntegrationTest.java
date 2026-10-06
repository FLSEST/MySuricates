package com.automation.config;

import com.automation.service.system.TailscaleSelfQuery;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS end to end through the real filter chain (a8056256): the default admits localhost only, a
 * foreign tailnet or a lookalike {@code 100.*} host is turned away, this instance's own Tailscale
 * address gets in (remote-access is on in the integration preset), and no response grants
 * credentials. Tailscale itself is a fake: this instance is {@code mine.tail1234.ts.net} /
 * {@code 100.64.0.7}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "automation.security.enabled=true",
        "automation.security.api-key=test-api-key"
})
class CorsIntegrationTest {

    private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";
    private static final String OWN_NAME = "https://mine.tail1234.ts.net:3000";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TailscaleSelfQuery tailscale;

    @BeforeEach
    void thisInstanceIsInATailnet() {
        when(tailscale.ipv4()).thenReturn(Optional.of("100.64.0.7\n"));
        when(tailscale.statusJson()).thenReturn(Optional.of("""
                {"Self": {"DNSName": "mine.tail1234.ts.net."},
                 "Peer": {"nodekey:abc": {"DNSName": "peer.tail1234.ts.net."}}}"""));
    }

    @Test
    void foreignTailnetOrigin_isRejected() throws Exception {
        // used to match https://*.tail*.ts.net:* — i.e. every tailnet, including those reachable via funnel
        mockMvc.perform(get("/api/v1/system/health").header("Origin", "https://evil.tail0000.ts.net:3000"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(ALLOW_ORIGIN));
    }

    @Test
    void lookalikeHundredHost_isRejected() throws Exception {
        // used to match http://100.*:3000 — the star also covers dots and letters
        mockMvc.perform(get("/api/v1/system/health").header("Origin", "http://100.evil.example:3000"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(ALLOW_ORIGIN));
    }

    @Test
    void peerInTheOwnTailnet_isRejected() throws Exception {
        mockMvc.perform(get("/api/v1/system/health").header("Origin", "https://peer.tail1234.ts.net:3000"))
                .andExpect(status().isForbidden());
    }

    @Test
    void localhostDevServer_isAllowed_withoutCredentials() throws Exception {
        mockMvc.perform(get("/api/v1/system/health").header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string(ALLOW_ORIGIN, "http://localhost:3000"))
                .andExpect(header().doesNotExist(ALLOW_CREDENTIALS));
    }

    @Test
    void ownTailscaleName_isAllowed_withoutCredentials() throws Exception {
        mockMvc.perform(get("/api/v1/system/health").header("Origin", OWN_NAME))
                .andExpect(status().isOk())
                .andExpect(header().string(ALLOW_ORIGIN, OWN_NAME))
                .andExpect(header().doesNotExist(ALLOW_CREDENTIALS));
    }

    @Test
    void ownTailscaleIp_isAllowed() throws Exception {
        mockMvc.perform(get("/api/v1/system/health").header("Origin", "http://100.64.0.7:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string(ALLOW_ORIGIN, "http://100.64.0.7:3000"));
    }

    @Test
    void preflightFromHttpsDevServer_isAnswered_withoutCredentials() throws Exception {
        mockMvc.perform(options("/api/v1/system/health")
                        .header("Origin", "https://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "X-API-Key, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(ALLOW_ORIGIN, "https://localhost:5173"))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")))
                .andExpect(header().doesNotExist(ALLOW_CREDENTIALS));
    }

    @Test
    void webSocketEndpoint_foreignOrigin_isRejected() throws Exception {
        // Also the SockJS entry under /ws: a foreign origin does not get as far as the handshake.
        mockMvc.perform(get("/ws/info").header("Origin", "https://evil.tail0000.ts.net:3000"))
                .andExpect(status().isForbidden());
    }

    @Test
    void webSocketEndpoint_ownOrigin_isServed_withoutCredentials() throws Exception {
        mockMvc.perform(get("/ws/info").header("Origin", OWN_NAME))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(ALLOW_CREDENTIALS));
    }

    @Test
    void liveUpdateHandshake_ownOrigin_passesEveryCheckUpToTheUpgrade() throws Exception {
        // 400 means: both origin checks (ours and Spring's) and the ticket have
        // passed, only MockMvc's missing upgrade header stops it. That way the
        // live updates via the Vite dev server over Tailscale are preserved.
        mockMvc.perform(get("/ws/000/abcdefgh/websocket").param("ticket", issueTicket()).header("Origin", OWN_NAME))
                .andExpect(status().isBadRequest());
    }

    @Test
    void liveUpdateHandshake_ownOriginWithoutTicket_stopsAtTheTicket() throws Exception {
        mockMvc.perform(get("/ws/000/abcdefgh/websocket").header("Origin", OWN_NAME))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void terminalHandshake_ownOrigin_passesEveryCheckUpToTheUpgrade() throws Exception {
        mockMvc.perform(get("/ws/terminal/abc").param("ticket", issueTicket()).header("Origin", OWN_NAME))
                .andExpect(status().isBadRequest());
    }

    private String issueTicket() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/ws-ticket").header("X-API-Key", "test-api-key"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.ticket");
    }
}
