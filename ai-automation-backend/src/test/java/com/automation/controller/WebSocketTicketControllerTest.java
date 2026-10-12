package com.automation.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ticket endpoint is the only way to authenticate a WebSocket handshake, so it must itself be
 * protected by the API key header — the whole point is that the key never reaches a URL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {
        "automation.security.enabled=true",
        "automation.security.api-key=test-api-key"
})
class WebSocketTicketControllerTest {

    private static final String VALID_API_KEY = "test-api-key";
    private static final String API_KEY_HEADER = "X-API-Key";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void withoutApiKeyTheEndpointIsRefused() throws Exception {
        // Spring Security answers unauthenticated API calls with 403 (see SecurityConfigIntegrationTest)
        mockMvc.perform(post("/api/v1/ws-ticket"))
                .andExpect(status().isForbidden());
    }

    @Test
    void withWrongApiKeyTheEndpointIsRefused() throws Exception {
        mockMvc.perform(post("/api/v1/ws-ticket").header(API_KEY_HEADER, "wrong-key"))
                .andExpect(status().isForbidden());
    }

    @Test
    void withApiKeyAFreshTicketIsIssued() throws Exception {
        mockMvc.perform(post("/api/v1/ws-ticket").header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket").isString())
                .andExpect(jsonPath("$.ticket").value(org.hamcrest.Matchers.matchesPattern("[A-Za-z0-9_-]{43}")))
                .andExpect(jsonPath("$.expiresInSeconds").value(30));
    }

    @Test
    void everyCallIssuesADifferentTicket() throws Exception {
        assertThat(issueTicket()).isNotEqualTo(issueTicket());
    }

    private String issueTicket() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/ws-ticket").header(API_KEY_HEADER, VALID_API_KEY))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.ticket");
    }
}
