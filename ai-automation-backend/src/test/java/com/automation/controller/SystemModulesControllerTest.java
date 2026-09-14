package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.exception.ErrorCode;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ModuleChangeException;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleOverrideService;
import com.automation.modules.ModuleRegistry;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemModulesController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemModulesControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private ModuleOverrideService overrides;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    @TestConfiguration
    static class Modules {
        @Bean
        ModuleRegistry moduleRegistry() {
            return ModuleRegistry.of(Map.of("ui-editable", "true", "webhooks", "true"), Map.of());
        }
    }

    @Test
    void switchReturnsTheModuleState() throws Exception {
        mockMvc.perform(put("/api/v1/system/modules/webhooks")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uiEditable").value(true))
                .andExpect(jsonPath("$.preset").value("core"))
                .andExpect(jsonPath("$.modules[?(@.name=='webhooks')].enabled").value(true))
                .andExpect(jsonPath("$.modules[?(@.name=='webhooks')].configured").value(true))
                .andExpect(jsonPath("$.modules[?(@.name=='webhooks')].overridden").value(false));

        verify(overrides).setEnabled("webhooks", true);
    }

    @Test
    void missingEnabledIsRejected() throws Exception {
        mockMvc.perform(put("/api/v1/system/modules/webhooks").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(overrides);
    }

    @Test
    void refusalsKeepTheirErrorCode() throws Exception {
        when(overrides.setEnabled(anyString(), anyBoolean())).thenThrow(new ModuleChangeException(
                ErrorCode.MODULES_NOT_EDITABLE, "Modules cannot be switched at runtime on this instance"));

        mockMvc.perform(put("/api/v1/system/modules/webhooks")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULES_NOT_EDITABLE"));
    }

    @Test
    void resetDelegates() throws Exception {
        when(overrides.resetToConfig("chains")).thenReturn(
                new ModuleRegistry.ModuleStatus(ModuleCatalog.CHAINS, false, false, false));

        mockMvc.perform(delete("/api/v1/system/modules/chains/override"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules").isArray());

        verify(overrides).resetToConfig("chains");
    }
}
