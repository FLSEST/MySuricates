package com.automation.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runtime module switches on the real stack: with automation.modules.ui-editable=true a switch in
 * the settings takes effect for the very next request, survives in app_settings and can be reset.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {"automation.modules.preset=core", "automation.modules.ui-editable=true"})
class ModuleUiToggleIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.automation.modules.ModuleRegistry registry;

    @AfterEach
    void tearDown() {
        registry.applyUiOverrides(Map.of());
        jdbc.update("DELETE FROM app_settings WHERE key = 'modules.ui-overrides'");
    }

    @Test
    void switchTakesEffectImmediatelyIsStoredAndCanBeReset() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks")).andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/system/modules/webhooks").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules[?(@.name=='webhooks')].overridden").value(true));
        mockMvc.perform(get("/api/v1/webhooks")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/system/capabilities"))
                .andExpect(jsonPath("$.modulesUiEditable").value(true))
                .andExpect(jsonPath("$.modules[?(@.name=='webhooks')].enabled").value(true));
        assertThat(jdbc.queryForObject("SELECT value FROM app_settings WHERE key = 'modules.ui-overrides'", String.class))
                .isEqualTo("{\"webhooks\":true}");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM activity_log WHERE category = 'SECURITY' AND message LIKE 'Module ''webhooks'' enabled%'",
                Integer.class)).isPositive();

        mockMvc.perform(delete("/api/v1/system/modules/webhooks/override")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/webhooks")).andExpect(status().isNotFound());
    }

    @Test
    void hardeningAndBrokenDependenciesAreRefused() throws Exception {
        mockMvc.perform(put("/api/v1/system/modules/sealing").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        // The MCP server is a feature module, but only the startup configuration may turn it on.
        mockMvc.perform(put("/api/v1/system/modules/mcp").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        mockMvc.perform(post("/api/v1/mcp").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/system/capabilities"))
                .andExpect(jsonPath("$.modules[?(@.name=='mcp')].startupOnly").value(true))
                .andExpect(jsonPath("$.modules[?(@.name=='mcp')].enabled").value(false));
        mockMvc.perform(put("/api/v1/system/modules/wishes").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }
}
