package com.automation.service.system;

import com.automation.config.BackupConfig;
import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;
import com.automation.repository.AppSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class SystemSettingsService {

    static final String BACKUP_RETAIN_COUNT = "backup-retain-count";

    private static final Set<String> ALLOWED_SETTINGS = Set.of(
            "auto-todo-default", "auto-repair-default", "auto-repair-mode",
            "auto-repair-max-retries", "auto-repair-timeout-seconds",
            "default-ai-provider", "assistant-thinking-phrases", "assistant-model",
            "suggestions-model", "suggestions-enabled",
            BACKUP_RETAIN_COUNT,
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
            throw new LocalizedArgumentException(ErrorMessage.UNKNOWN_SETTING, Map.of("key", String.valueOf(key)));
        }
        return appSettingsRepository.get(key);
    }

    public void setSetting(String key, String value) {
        if (!ALLOWED_SETTINGS.contains(key)) {
            throw new LocalizedArgumentException(ErrorMessage.UNKNOWN_SETTING, Map.of("key", String.valueOf(key)));
        }
        if (value == null) {
            throw new IllegalArgumentException("Value must not be null");
        }
        if (BACKUP_RETAIN_COUNT.equals(key)) {
            // Checked before anything is stored (TODO 205ebd8f): the request used to fail after the
            // invalid value was already in app_settings.
            value = String.valueOf(retainCount(value));
        }
        appSettingsRepository.set(key, value);

        // Services that cache a setting re-read it here: AiProviderRegistry, WorkflowTaggingService,
        // SemanticWorkflowSearchService and the assistant module (AssistantSettingsListener).
        eventPublisher.publishEvent(new AppSettingChangedEvent(key));
    }

    /**
     * How many backups the rotation keeps: the stored {@code backup-retain-count}, else
     * {@code automation.backup.retain-count}. Read on every rotation (TODO 205ebd8f) — before, the
     * stored value only reached {@link BackupConfig} when it was set, so after a restart the default
     * applied again, already in the startup rotation. A stored value outside 1–365 (the old write path
     * stored it unchecked) counts as not set.
     */
    public int backupRetainCount() {
        Optional<String> stored = appSettingsRepository.get(BACKUP_RETAIN_COUNT);
        if (stored.isPresent()) {
            try {
                return retainCount(stored.get());
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring stored {} '{}': {}", BACKUP_RETAIN_COUNT, stored.get(), e.getMessage());
            }
        }
        return backupConfig.getRetainCount();
    }

    private static int retainCount(String value) {
        int count;
        try {
            count = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("backup-retain-count must be a number between 1 and 365");
        }
        if (count < 1 || count > 365) {
            throw new IllegalArgumentException("backup-retain-count must be between 1 and 365");
        }
        return count;
    }
}
