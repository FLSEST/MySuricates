package com.automation.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end check that the MappedInterceptor is picked up by the real handler mapping:
 * the integration profile runs preset "full", and this class switches only webhooks off.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "automation.modules.webhooks=false")
class ModuleGuardIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void disabledModuleAnswers404WithModuleDisabled() throws Exception {
        mockMvc.perform(get("/api/v1/webhooks"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODULE_DISABLED"))
                .andExpect(jsonPath("$.error").value(containsString("automation.modules.webhooks=true")));
    }

    @Test
    void publicWebhookTriggerIsGuardedToo() throws Exception {
        mockMvc.perform(post("/api/v1/webhook/trigger/some-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
    }

    @Test
    void coreEndpointsStayReachable() throws Exception {
        mockMvc.perform(get("/api/v1/system/health"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/todos"))
                .andExpect(status().isOk());
    }

    @Test
    void runtimeSwitchesAreRefusedWithoutUiEditable() throws Exception {
        mockMvc.perform(put("/api/v1/system/modules/webhooks")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULES_NOT_EDITABLE"));
        mockMvc.perform(get("/api/v1/webhooks")).andExpect(status().isNotFound());
    }

    @Test
    void enabledModuleIsNotGuarded() throws Exception {
        mockMvc.perform(get("/api/v1/chains"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("MODULE_DISABLED"))));
    }
}
