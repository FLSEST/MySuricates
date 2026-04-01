package com.automation.service.system;

import com.automation.repository.AppSettingsRepository;
import com.automation.service.AssistantService;
import com.automation.service.PageSuggestionService;
import com.automation.service.ai.AiProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class SystemSettingsService {

    private static final Set<String> ALLOWED_SETTINGS = Set.of(
            "auto-todo-default", "auto-repair-default", "auto-repair-mode",
            "auto-repair-max-retries", "auto-repair-timeout-seconds",
            "default-ai-provider", "assistant-thinking-phrases", "assistant-model", "suggestions-model"
    );

    private final AppSettingsRepository appSettingsRepository;
    private final AiProviderRegistry aiProviderRegistry;

    @Autowired
    @Lazy
    private AssistantService assistantService;

    @Autowired
    @Lazy
    private PageSuggestionService pageSuggestionService;

    public SystemSettingsService(AppSettingsRepository appSettingsRepository, AiProviderRegistry aiProviderRegistry) {
        this.appSettingsRepository = appSettingsRepository;
        this.aiProviderRegistry = aiProviderRegistry;
    }

    public boolean isAllowedKey(String key) {
        return ALLOWED_SETTINGS.contains(key);
    }

    public Optional<String> getSetting(String key) {
        if (!ALLOWED_SETTINGS.contains(key)) {
            throw new IllegalArgumentException("Unknown setting: " + key);
        }
        return appSettingsRepository.get(key);
    }

    public void setSetting(String key, String value) {
        if (!ALLOWED_SETTINGS.contains(key)) {
            throw new IllegalArgumentException("Unknown setting: " + key);
        }
        if (value == null) {
            throw new IllegalArgumentException("Value must not be null");
        }
        appSettingsRepository.set(key, value);

        if ("assistant-model".equals(key)) {
            assistantService.refreshCachedModel();
        }
        if ("suggestions-model".equals(key)) {
            pageSuggestionService.refreshCachedModel();
        }
        if ("default-ai-provider".equals(key)) {
            aiProviderRegistry.refreshDefaultProviderCache();
        }
    }
}
