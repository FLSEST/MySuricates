package com.automation.integration;

import com.automation.modules.ModuleRegistry;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.assistant.AssistantActionSuggestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A setting written over the API reaches the cached copies through {@code AppSettingChangedEvent}: a core
 * cache follows right away, the assistant module's only while the module is on — and switching the
 * module on at runtime catches up on what it skipped.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = {"automation.modules.preset=core", "automation.modules.ui-editable=true"})
class AppSettingChangedIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ModuleRegistry registry;
    @Autowired private AiProviderRegistry aiProviderRegistry;
    @Autowired private AssistantActionSuggestionService actionSuggestionService;

    @BeforeEach
    void setUp() {
        resetSettings();
    }

    @AfterEach
    void tearDown() {
        registry.applyUiOverrides(Map.of());
        jdbc.update("DELETE FROM app_settings WHERE key = 'modules.ui-overrides'");
        resetSettings();
    }

    @Test
    void coreCacheFollowsTheSettingRightAway() throws Exception {
        String current = aiProviderRegistry.getDefaultProviderId();
        String other = aiProviderRegistry.getAvailableProviderIds().stream()
                .filter(id -> !id.equals(current)).findFirst().orElseThrow();

        putSetting("default-ai-provider", other);

        assertThat(aiProviderRegistry.getDefaultProviderId()).isEqualTo(other);
    }

    @Test
    void assistantCacheWaitsForItsModuleAndCatchesUpWhenSwitchedOn() throws Exception {
        assertThat(actionSuggestionService.isEnabled()).isTrue();

        putSetting("suggestions-enabled", "false");
        assertThat(actionSuggestionService.isEnabled()).as("module off: the listener is skipped").isTrue();

        mockMvc.perform(put("/api/v1/system/modules/assistant")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        assertThat(actionSuggestionService.isEnabled()).as("switched on: caught up").isFalse();

        putSetting("suggestions-enabled", "true");
        assertThat(actionSuggestionService.isEnabled()).as("module on: follows right away").isTrue();
    }

    private void putSetting(String key, String value) throws Exception {
        mockMvc.perform(put("/api/v1/system/settings/" + key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":\"" + value + "\"}"))
                .andExpect(status().isOk());
    }

    /** Both caches back to "no stored value", whatever a test left behind. */
    private void resetSettings() {
        jdbc.update("DELETE FROM app_settings WHERE key IN ('default-ai-provider', 'suggestions-enabled')");
        aiProviderRegistry.refreshDefaultProviderCache();
        actionSuggestionService.refreshCache();
    }
}
