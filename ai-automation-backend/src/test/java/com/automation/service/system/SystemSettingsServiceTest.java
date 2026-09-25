package com.automation.service.system;

import com.automation.config.BackupConfig;
import com.automation.repository.AppSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SystemSettingsServiceTest {

    @Mock private AppSettingsRepository appSettingsRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Spy private BackupConfig backupConfig = new BackupConfig();

    private SystemSettingsService service;

    @BeforeEach
    void setUp() {
        service = new SystemSettingsService(appSettingsRepository, backupConfig, eventPublisher);
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
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void setSetting_nullValue_throws() {
        assertThatThrownBy(() -> service.setSetting("auto-todo-default", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Value must not be null");

        verifyNoInteractions(appSettingsRepository);
        verifyNoInteractions(eventPublisher);
    }

    /** The keys whose caches used to be refreshed by direct calls — the listeners pick them up now. */
    @ParameterizedTest
    @ValueSource(strings = {"assistant-model", "suggestions-model", "suggestions-enabled", "default-ai-provider"})
    void setSetting_cachedKey_announcesTheChangeAfterStoringIt(String key) {
        service.setSetting(key, "value");

        var order = inOrder(appSettingsRepository, eventPublisher);
        order.verify(appSettingsRepository).set(key, "value");
        order.verify(eventPublisher).publishEvent(new AppSettingChangedEvent(key));
        verifyNoMoreInteractions(eventPublisher);
    }

    @Test
    void setSetting_otherKey_isAnnouncedAsWell() {
        service.setSetting("auto-repair-default", "true");

        verify(appSettingsRepository).set("auto-repair-default", "true");
        verify(eventPublisher).publishEvent(new AppSettingChangedEvent("auto-repair-default"));
    }

    /** 205ebd8f: the count is stored (normalised) and read by the rotation — no copy in BackupConfig any more. */
    @Test
    void setSetting_backupRetainCount_storesTheCountAndAnnounces() {
        service.setSetting("backup-retain-count", " 14 ");

        var order = inOrder(appSettingsRepository, eventPublisher);
        order.verify(appSettingsRepository).set("backup-retain-count", "14");
        order.verify(eventPublisher).publishEvent(new AppSettingChangedEvent("backup-retain-count"));
        verify(backupConfig, never()).setRetainCount(anyInt());
    }

    /** 205ebd8f: the range is checked before anything is stored — the old path stored first, then threw. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "366", "-1", "abc", "1.5", " "})
    void setSetting_invalidBackupRetainCount_storesNothingAndAnnouncesNothing(String value) {
        assertThatThrownBy(() -> service.setSetting("backup-retain-count", value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 365");

        verify(appSettingsRepository, never()).set(any(), any());
        verify(backupConfig, never()).setRetainCount(anyInt());
        verifyNoInteractions(eventPublisher);
    }

    // --- backupRetainCount (205ebd8f: the stored value holds after a restart) ---

    /** A fresh service over the stored settings — as after a restart, nothing is held in memory. */
    @Test
    void backupRetainCount_isTheStoredValue_soItHoldsAfterARestart() {
        when(appSettingsRepository.get("backup-retain-count")).thenReturn(Optional.of("12"));
        SystemSettingsService afterRestart = new SystemSettingsService(appSettingsRepository, new BackupConfig(), eventPublisher);

        assertThat(afterRestart.backupRetainCount()).isEqualTo(12);
    }

    @Test
    void backupRetainCount_withoutStoredValue_isTheConfiguredCount() {
        when(appSettingsRepository.get("backup-retain-count")).thenReturn(Optional.empty());
        backupConfig.setRetainCount(9);

        assertThat(service.backupRetainCount()).isEqualTo(9);
    }

    /** A value the old write path stored unchecked counts as not set. */
    @ParameterizedTest
    @ValueSource(strings = {"0", "400", "viele"})
    void backupRetainCount_ignoresAnUnusableStoredValue(String stored) {
        when(appSettingsRepository.get("backup-retain-count")).thenReturn(Optional.of(stored));

        assertThat(service.backupRetainCount()).isEqualTo(30);
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
