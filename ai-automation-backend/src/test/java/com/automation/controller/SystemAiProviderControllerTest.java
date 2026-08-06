package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.ai.PiCatalogService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.system.SystemIdentityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SystemAiProviderController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemAiProviderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiProviderRegistry aiProviderRegistry;

    @MockitoBean
    private PiCatalogService piCatalogService;

    @MockitoBean
    private SystemIdentityService systemIdentity;

    @MockitoBean
    private ActivityLogService activityLogService;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    @Test
    void aiProviders_returnsProviderList() throws Exception {
        when(aiProviderRegistry.getAvailableProviderIds())
                .thenReturn(java.util.List.of("CLAUDE", "CODEX"));
        when(aiProviderRegistry.getDefaultProviderId())
                .thenReturn("CLAUDE");

        mockMvc.perform(get("/api/v1/system/ai-providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providers").isArray())
                .andExpect(jsonPath("$.providers.length()").value(2));
    }

    @Test
    void capabilities_returnsRoutesAndBlockTypes() throws Exception {
        when(systemIdentity.getVersion()).thenReturn("0.7.2-SNAPSHOT");
        when(systemIdentity.getBuildType()).thenReturn("SNAPSHOT");

        mockMvc.perform(get("/api/v1/system/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiVersion").value("v1"))
                .andExpect(jsonPath("$.appVersion").value("0.7.2-SNAPSHOT"))
                .andExpect(jsonPath("$.routes[*].path", hasItem("/api/v1/system/capabilities")))
                .andExpect(jsonPath("$.blockTypes[*].type", hasItem("shell")))
                .andExpect(jsonPath("$.generatedAt").exists());
    }
}
