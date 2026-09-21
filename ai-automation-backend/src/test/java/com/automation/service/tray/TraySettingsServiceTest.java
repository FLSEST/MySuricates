package com.automation.service.tray;

import com.automation.config.TrayConfig;
import com.automation.repository.AppSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TraySettingsServiceTest {

    @Mock
    private AppSettingsRepository settings;

    private TrayConfig config;
    private TraySettingsService service;

    @BeforeEach
    void setUp() {
        config = new TrayConfig();
        service = new TraySettingsService(settings, config);
    }

    @Test
    void current_usesQuietDefaults() {
        TraySettingsService.Preferences result = service.current();

        assertThat(result.popupsEnabled()).isTrue();
        assertThat(result.notifyApprovals()).isTrue();
        assertThat(result.notifyFailures()).isTrue();
        assertThat(result.notifySuccess()).isFalse();
        assertThat(result.publicUrl()).isEqualTo("http://localhost:3000");
    }

    @Test
    void browserBaseUrl_prefersStoredHttpsUrlAndStripsSlash() {
        when(settings.get(TraySettingsService.PUBLIC_URL))
                .thenReturn(Optional.of("https://mysuricates.example.com/"));

        assertThat(service.browserBaseUrl()).isEqualTo("https://mysuricates.example.com");
    }

    @Test
    void browserBaseUrl_usesConfiguredPublicUrlBeforeInternalHealthUrl() {
        config.setPublicUrl("https://remote.example.com/");

        assertThat(service.browserBaseUrl()).isEqualTo("https://remote.example.com");
    }

    @Test
    void update_persistsPopupChoicesAndPublicUrl() {
        service.update(Map.of(
                "popupsEnabled", false,
                "notifyApprovals", false,
                "notifyFailures", true,
                "notifySuccess", false,
                "publicUrl", "https://mysuricates.example.com/"
        ));

        verify(settings).set(TraySettingsService.POPUPS_ENABLED, "false");
        verify(settings).set(TraySettingsService.NOTIFY_APPROVALS, "false");
        verify(settings).set(TraySettingsService.NOTIFY_FAILURES, "true");
        verify(settings).set(TraySettingsService.NOTIFY_SUCCESS, "false");
        verify(settings).set(TraySettingsService.PUBLIC_URL, "https://mysuricates.example.com");
    }

    @Test
    void update_rejectsExternalHttpUrl() {
        assertThatThrownBy(() -> service.update(Map.of("publicUrl", "http://mysuricates.example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    void update_allowsHttpForLocalDevelopment() {
        service.update(Map.of("publicUrl", "http://localhost:3000/"));

        verify(settings).set(TraySettingsService.PUBLIC_URL, "http://localhost:3000");
    }
}
