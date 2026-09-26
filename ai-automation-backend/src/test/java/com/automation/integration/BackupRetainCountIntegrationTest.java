package com.automation.integration;

import com.automation.config.BackupConfig;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.system.SystemSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code backup-retain-count} against the real {@code app_settings} table (TODO 205ebd8f): an invalid
 * value is refused before it is stored, and a stored value holds after a restart — the rotation reads
 * it, nothing about it lives only in memory.
 */
@SpringBootTest
@ActiveProfiles("integration")
class BackupRetainCountIntegrationTest {

    private static final String KEY = "backup-retain-count";

    @Autowired private SystemSettingsService settings;
    @Autowired private AppSettingsRepository appSettingsRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;

    @AfterEach
    void tearDown() {
        appSettingsRepository.delete(KEY);
    }

    @Test
    void anInvalidValueIsRefusedAndNotStored() {
        settings.setSetting(KEY, "12");

        assertThatThrownBy(() -> settings.setSetting(KEY, "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.setSetting(KEY, "viele")).isInstanceOf(IllegalArgumentException.class);

        assertThat(appSettingsRepository.get(KEY)).contains("12");
    }

    /** A fresh service with a freshly bound BackupConfig over the same database — what a restart leaves. */
    @Test
    void theStoredValueHoldsAfterARestart() {
        settings.setSetting(KEY, " 7 ");

        SystemSettingsService afterRestart = new SystemSettingsService(appSettingsRepository, new BackupConfig(), eventPublisher);

        assertThat(afterRestart.backupRetainCount()).isEqualTo(7);
        assertThat(appSettingsRepository.get(KEY)).contains("7");
    }
}
