package com.automation.service.system;

import com.automation.config.BackupConfig;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.AssistantService;
import com.automation.service.PageSuggestionService;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.assistant.AssistantActionSuggestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SystemSettingsServiceTest {

    @Mock private AppSettingsRepository appSettingsRepository;
    @Mock private AiProviderRegistry aiProviderRegistry;
    @Mock private AssistantService assistantService;
    @Mock private PageSuggestionService pageSuggestionService;
    @Mock private AssistantActionSuggestionService actionSuggestionService;
    @Spy private BackupConfig backupConfig = new BackupConfig();

    private SystemSettingsService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new SystemSettingsService(appSettingsRepository, aiProviderRegistry, backupConfig);

        // Inject @Lazy @Autowired fields via reflection
        setField(service, "assistantService", assistantService);
        setField(service, "pageSuggestionService", pageSuggestionService);
        setField(service, "actionSuggestionService", actionSuggestionService);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // --- isAllowedKey ---

    @Test
    void isAllowedKey_knownSetting_returnsTrue() {
        assertThat(service.isAllowedKey("auto-todo-default")).isTrue();
        assertThat(service.isAllowedKey("default-ai-provider")).isTrue();
        assertThat(service.isAllowedKey("assistant-model")).isTrue();
        assertThat(service.isAllowedKey("suggestions-model")).isTrue();
    }

    @Test
    void isAllowedKey_unknownSetting_returnsFalse() {
        assertThat(service.isAllowedKey("unknown-key")).isFalse();
        assertThat(service.isAllowedKey("")).isFalse();
        assertThat(service.isAllowedKey("api-key")).isFalse();
    }

    // --- getSetting ---

    @Test
    void getSetting_allowedKey_delegatesToRepository() {
        when(appSettingsRepository.get("assistant-model")).thenReturn(Optional.of("claude-sonnet-4-6"));

        Optional<String> result = service.getSetting("assistant-model");

        assertThat(result).contains("claude-sonnet-4-6");
        verify(appSettingsRepository).get("assistant-model");
    }

    @Test
    void getSetting_allowedKey_noValue_returnsEmpty() {
        when(appSettingsRepository.get("auto-todo-default")).thenReturn(Optional.empty());

        Optional<String> result = service.getSetting("auto-todo-default");

        assertThat(result).isEmpty();
    }

    @Test
    void getSetting_unknownKey_throws() {
        assertThatThrownBy(() -> service.getSetting("unknown-key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown setting: unknown-key");
    }

    // --- setSetting ---

    @Test
    void setSetting_allowedKey_persistsValue() {
        service.setSetting("auto-todo-default", "track");

        verify(appSettingsRepository).set("auto-todo-default", "track");
    }

    @Test
    void setSetting_unknownKey_throws() {
        assertThatThrownBy(() -> service.setSetting("unknown-key", "value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown setting: unknown-key");

        verifyNoInteractions(appSettingsRepository);
    }

    @Test
    void setSetting_nullValue_throws() {
        assertThatThrownBy(() -> service.setSetting("auto-todo-default", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Value must not be null");

        verifyNoInteractions(appSettingsRepository);
    }

    @Test
    void setSetting_assistantModel_refreshesCachedModel() {
        service.setSetting("assistant-model", "claude-opus-4-6");

        verify(appSettingsRepository).set("assistant-model", "claude-opus-4-6");
        verify(assistantService).refreshCachedModel();
        verifyNoInteractions(pageSuggestionService);
        verifyNoInteractions(aiProviderRegistry);
    }

    @Test
    void setSetting_suggestionsModel_refreshesCachedModel() {
        service.setSetting("suggestions-model", "claude-haiku-4-5");

        verify(appSettingsRepository).set("suggestions-model", "claude-haiku-4-5");
        verify(pageSuggestionService).refreshCachedModel();
        verify(actionSuggestionService).refreshCache();
        verifyNoInteractions(assistantService);
        verifyNoInteractions(aiProviderRegistry);
    }

    @Test
    void setSetting_suggestionsEnabled_refreshesActionSuggestionCache() {
        service.setSetting("suggestions-enabled", "false");

        verify(appSettingsRepository).set("suggestions-enabled", "false");
        verify(actionSuggestionService).refreshCache();
        verifyNoInteractions(assistantService);
        verifyNoInteractions(pageSuggestionService);
        verifyNoInteractions(aiProviderRegistry);
    }

    @Test
    void setSetting_defaultAiProvider_refreshesProviderCache() {
        service.setSetting("default-ai-provider", "CODEX");

        verify(appSettingsRepository).set("default-ai-provider", "CODEX");
        verify(aiProviderRegistry).refreshDefaultProviderCache();
        verifyNoInteractions(assistantService);
        verifyNoInteractions(pageSuggestionService);
    }

    @Test
    void setSetting_nonTriggerKey_noSideEffects() {
        service.setSetting("auto-repair-default", "true");

        verify(appSettingsRepository).set("auto-repair-default", "true");
        verifyNoInteractions(assistantService);
        verifyNoInteractions(pageSuggestionService);
        verifyNoInteractions(actionSuggestionService);
        verifyNoInteractions(aiProviderRegistry);
    }

    // --- all allowed keys ---

    @Test
    void allAllowedKeys_areRecognized() {
        String[] expectedKeys = {
                "auto-todo-default", "auto-repair-default", "auto-repair-mode",
                "auto-repair-max-retries", "auto-repair-timeout-seconds",
                "default-ai-provider", "assistant-thinking-phrases",
                "assistant-model", "suggestions-model", "suggestions-enabled"
        };

        for (String key : expectedKeys) {
            assertThat(service.isAllowedKey(key))
                    .as("Key '%s' should be allowed", key)
                    .isTrue();
        }
    }
}
