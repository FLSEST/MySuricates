package com.automation.service.system;

import com.automation.config.BackupConfig;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.AssistantService;
import com.automation.service.PageSuggestionService;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.assistant.AssistantActionSuggestionService;
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
            "default-ai-provider", "assistant-thinking-phrases", "assistant-model",
            "suggestions-model", "suggestions-enabled",
            "backup-retain-count"
    );

    private final AppSettingsRepository appSettingsRepository;
    private final AiProviderRegistry aiProviderRegistry;
    private final BackupConfig backupConfig;

    @Autowired
    @Lazy
    private AssistantService assistantService;

    @Autowired
    @Lazy
    private PageSuggestionService pageSuggestionService;

    @Autowired
    @Lazy
    private AssistantActionSuggestionService actionSuggestionService;

    public SystemSettingsService(AppSettingsRepository appSettingsRepository, AiProviderRegistry aiProviderRegistry, BackupConfig backupConfig) {
        this.appSettingsRepository = appSettingsRepository;
        this.aiProviderRegistry = aiProviderRegistry;
        this.backupConfig = backupConfig;
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

        if ("backup-retain-count".equals(key)) {
            int count = Integer.parseInt(value);
            if (count < 1 || count > 365) {
                throw new IllegalArgumentException("backup-retain-count must be between 1 and 365");
            }
            backupConfig.setRetainCount(count);
        }
        if ("assistant-model".equals(key)) {
            assistantService.refreshCachedModel();
        }
        if ("suggestions-model".equals(key)) {
            pageSuggestionService.refreshCachedModel();
            actionSuggestionService.refreshCache();
        }
        if ("suggestions-enabled".equals(key)) {
            actionSuggestionService.refreshCache();
        }
        if ("default-ai-provider".equals(key)) {
            aiProviderRegistry.refreshDefaultProviderCache();
        }
    }
}
