package com.automation.service.system;

import com.automation.config.BackupConfig;
import com.automation.repository.AppSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class SystemSettingsService {

    private static final Set<String> ALLOWED_SETTINGS = Set.of(
            "auto-todo-default", "auto-repair-default", "auto-repair-mode",
            "auto-repair-max-retries", "auto-repair-timeout-seconds",
            "default-ai-provider", "assistant-thinking-phrases", "assistant-model",
            "suggestions-model", "suggestions-enabled",
            "backup-retain-count",
            "github.repo-allowlist"
    );

    private final AppSettingsRepository appSettingsRepository;
    private final BackupConfig backupConfig;
    private final ApplicationEventPublisher eventPublisher;

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
        // Services that cache a setting re-read it here: AiProviderRegistry, WorkflowTaggingService,
        // SemanticWorkflowSearchService and the assistant module (AssistantSettingsListener).
        eventPublisher.publishEvent(new AppSettingChangedEvent(key));
    }
}
