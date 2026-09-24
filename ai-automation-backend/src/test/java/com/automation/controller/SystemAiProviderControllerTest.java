package com.automation.controller;

import com.automation.dto.response.SystemCapabilitiesResponse;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.ai.PiCatalogService;
import com.automation.service.ai.catalog.ModelCatalog;
import com.automation.service.ai.catalog.ModelCatalogService;
import com.automation.service.ai.status.AiProviderStatusReport;
import com.automation.service.ai.status.AiProviderStatusService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.system.ContainerAccessReport;
import com.automation.service.system.SystemIdentityService;
import com.automation.service.workflow.RunDeletionPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private ModelCatalogService modelCatalogService;

    @MockitoBean
    private SystemIdentityService systemIdentity;

    @MockitoBean
    private ActivityLogService activityLogService;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    @MockitoBean
    private RunDeletionPolicy runDeletionPolicy;

    @MockitoBean
    private ContainerAccessReport containerAccessReport;

    @MockitoBean
    private AiProviderStatusService aiProviderStatusService;

    /**
     * Real registry, not a mock: preset research with webhooks switched on by override, and
     * ui-editable so {@link #capabilities_blockTypesRespectTheModuleGate} can flip a module at
     * runtime the same way {@code ModuleOverrideService} does in production.
     */
    @TestConfiguration
    static class Modules {
        @Bean
        ModuleRegistry moduleRegistry() {
            return ModuleRegistry.of(
                    Map.of("preset", "research", "webhooks", "true", "ui-editable", "true"), Map.of());
        }
    }

    @Autowired
    private ModuleRegistry moduleRegistry;

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

    @Test
    void capabilities_reportWhatCameFromTheHost() throws Exception {
        when(containerAccessReport.report()).thenReturn(new SystemCapabilitiesResponse.ContainerAccessResponse(
                "container", "login", true, "/home/u/.config/mysuricates/staging.env", "STABLE_",
                java.util.List.of(new SystemCapabilitiesResponse.ProviderAccessResponse("claude", true, true, false))));

        mockMvc.perform(get("/api/v1/system/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.containerAccess.runtime").value("container"))
                .andExpect(jsonPath("$.containerAccess.aiFromHost").value("login"))
                .andExpect(jsonPath("$.containerAccess.dockerSocket").value(true))
                .andExpect(jsonPath("$.containerAccess.switchPrefix").value("STABLE_"))
                .andExpect(jsonPath("$.containerAccess.providers[0].id").value("claude"))
                .andExpect(jsonPath("$.containerAccess.providers[0].login").value(false));
    }

    @Test
    void capabilities_listsModulesWithStartupDecision() throws Exception {
        mockMvc.perform(get("/api/v1/system/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preset").value("research"))
                .andExpect(jsonPath("$.modules.length()").value(ModuleCatalog.values().length))
                .andExpect(jsonPath("$.modules[?(@.name=='chains')].enabled").value(true))
                .andExpect(jsonPath("$.modules[?(@.name=='webhooks')].enabled").value(true))
                .andExpect(jsonPath("$.modules[?(@.name=='webhooks')].risks[0]").value("network"))
                .andExpect(jsonPath("$.modules[?(@.name=='webhooks')].envVar").value("AUTOMATION_MODULES_WEBHOOKS"))
                .andExpect(jsonPath("$.modules[?(@.name=='terminal')].risks[0]").value("host-access"))
                .andExpect(jsonPath("$.modules[?(@.name=='wishes')].enabled").value(false))
                .andExpect(jsonPath("$.modules[?(@.name=='wishes')].requires[0]").value("chains"))
                .andExpect(jsonPath("$.modules[?(@.name=='provenance')].kind").value("hardening"))
                .andExpect(jsonPath("$.modules[?(@.name=='provenance')].enabled").value(true))
                .andExpect(jsonPath("$.modules[?(@.name=='sealing')].property").value("automation.hardening.sealing"));
    }

    /**
     * Exercises the actual filter predicate in {@link SystemAiProviderController#capabilities()},
     * not just its two halves in isolation (TODO f4231dc9, Fix-Runde 1): with the module off the
     * gated type is missing from {@code blockTypes}, with it on the type is back — and an ungated
     * type ('shell') is present either way, so the filter isn't accidentally dropping everything.
     */
    @Test
    void capabilities_blockTypesRespectTheModuleGate() throws Exception {
        moduleRegistry.applyUiOverrides(Map.of(ModuleCatalog.DASHBOARD_REPORT, false));
        try {
            mockMvc.perform(get("/api/v1/system/capabilities"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.blockTypes[*].type", not(hasItem("dashboard-report"))))
                    .andExpect(jsonPath("$.blockTypes[*].type", hasItem("shell")));

            moduleRegistry.applyUiOverrides(Map.of(ModuleCatalog.DASHBOARD_REPORT, true));

            mockMvc.perform(get("/api/v1/system/capabilities"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.blockTypes[*].type", hasItem("dashboard-report")))
                    .andExpect(jsonPath("$.blockTypes[*].type", hasItem("shell")));
        } finally {
            // Reset regardless of assertion outcome above: the ModuleRegistry bean is shared across
            // this test class's methods (WebMvcTest caches the context), a stuck override would leak.
            moduleRegistry.applyUiOverrides(Map.of());
        }
    }

    /**
     * The integration block refuses to run without its module; preset research leaves integrations
     * off, so the editor must not be offered the block either (TODO 560d18dd).
     */
    @Test
    void capabilities_doNotOfferTheIntegrationBlockWithoutItsModule() throws Exception {
        mockMvc.perform(get("/api/v1/system/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockTypes[*].type", not(hasItem("integration"))))
                .andExpect(jsonPath("$.blockTypes[*].type", hasItem("todo-create")));
    }

    @Test
    void models_returnsCatalogForProvider() throws Exception {
        when(modelCatalogService.getCatalog("PI", false)).thenReturn(ModelCatalog.available(
                "PI", "pi-cli", Instant.parse("2026-09-12T10:00:00Z"),
                List.of(ModelCatalog.ModelOption.of("openai-codex/gpt-5.5", "openai-codex · gpt-5.5"))));

        mockMvc.perform(get("/api/v1/system/ai-providers/PI/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerId").value("PI"))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.source").value("pi-cli"))
                .andExpect(jsonPath("$.models[0].value").value("openai-codex/gpt-5.5"));
    }

    @Test
    void models_unavailableCatalog_stillReturns200WithReason() throws Exception {
        when(modelCatalogService.getCatalog("CODEX", false))
                .thenReturn(ModelCatalog.unavailable("CODEX", "codex-cli", "codex not installed"));

        mockMvc.perform(get("/api/v1/system/ai-providers/CODEX/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").value("codex not installed"))
                .andExpect(jsonPath("$.models").isEmpty());
    }

    @Test
    void refreshModels_postForcesRefresh() throws Exception {
        when(modelCatalogService.getCatalog("CLAUDE", true)).thenReturn(ModelCatalog.available(
                "CLAUDE", "static-aliases", Instant.now(), List.of(ModelCatalog.ModelOption.of("opus", "opus"))));

        mockMvc.perform(post("/api/v1/system/ai-providers/CLAUDE/models/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].value").value("opus"));
    }

    @Test
    void models_unknownProvider_returns404() throws Exception {
        when(modelCatalogService.getCatalog("GEMINI", false))
                .thenThrow(new com.automation.exception.ResourceNotFoundException("AiProvider", "GEMINI"));

        mockMvc.perform(get("/api/v1/system/ai-providers/GEMINI/models"))
                .andExpect(status().isNotFound());
    }

    /** automation.executions.allow-delete reaches the UI through the capabilities response. */
    @Test
    void capabilities_reportsWhetherRunsMayBeDeleted() throws Exception {
        when(runDeletionPolicy.isAllowed()).thenReturn(false);
        mockMvc.perform(get("/api/v1/system/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runDeletionAllowed").value(false));

        when(runDeletionPolicy.isAllowed()).thenReturn(true);
        mockMvc.perform(get("/api/v1/system/capabilities"))
                .andExpect(jsonPath("$.runDeletionAllowed").value(true));
    }

    /** f5a273ae: the start page asks whether the AI CLIs are ready — names and commands, never a value. */
    @Test
    void aiProviderStatus_reportsStateAndCommandsPerProvider() throws Exception {
        when(aiProviderStatusService.report(false)).thenReturn(new AiProviderStatusReport(false,
                Instant.parse("2026-09-24T10:00:00Z"), List.of(
                        new AiProviderStatusReport.ProviderStatus("CLAUDE", "claude",
                                AiProviderStatusReport.State.READY, true, true,
                                AiProviderStatusReport.LoginVia.ENVIRONMENT, "CLAUDE_CODE_OAUTH_TOKEN",
                                "curl -fsSL https://claude.ai/install.sh | bash", "claude auth login", true),
                        new AiProviderStatusReport.ProviderStatus("CODEX", "codex",
                                AiProviderStatusReport.State.LOGIN_MISSING, true, false, null, null,
                                "npm install -g @openai/codex", "codex login", false))));

        mockMvc.perform(get("/api/v1/system/ai-providers/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.container").value(false))
                .andExpect(jsonPath("$.checkedAt").exists())
                .andExpect(jsonPath("$.providers[0].id").value("CLAUDE"))
                .andExpect(jsonPath("$.providers[0].state").value("READY"))
                .andExpect(jsonPath("$.providers[0].loginVia").value("ENVIRONMENT"))
                .andExpect(jsonPath("$.providers[0].loginSource").value("CLAUDE_CODE_OAUTH_TOKEN"))
                .andExpect(jsonPath("$.providers[0].defaultProvider").value(true))
                .andExpect(jsonPath("$.providers[1].state").value("LOGIN_MISSING"))
                .andExpect(jsonPath("$.providers[1].login").value("codex login"))
                .andExpect(jsonPath("$.providers[1].loginVia").doesNotExist());
    }

    @Test
    void aiProviderStatus_refreshLooksAgain() throws Exception {
        when(aiProviderStatusService.report(true)).thenReturn(new AiProviderStatusReport(true,
                Instant.parse("2026-09-24T10:01:00Z"), List.of()));

        mockMvc.perform(post("/api/v1/system/ai-providers/status/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.container").value(true));
    }
}
