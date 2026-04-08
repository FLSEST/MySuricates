package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class TrayConfigTest {

    private TrayConfig config;

    @BeforeEach
    void setUp() {
        config = new TrayConfig();
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(TrayConfig.class.isAnnotationPresent(Configuration.class))
                .as("TrayConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveConfigurationPropertiesAnnotation_when_classIsInspected() {
        assertThat(TrayConfig.class.isAnnotationPresent(ConfigurationProperties.class))
                .as("TrayConfig must be annotated with @ConfigurationProperties")
                .isTrue();
    }

    @Test
    void should_haveCorrectPrefix_when_configurationPropertiesIsInspected() {
        ConfigurationProperties annotation = TrayConfig.class.getAnnotation(ConfigurationProperties.class);
        assertThat(annotation.prefix()).isEqualTo("automation.tray");
    }

    // --- Default value tests ---

    @Test
    void should_returnTrue_when_enabledDefaultIsQueried() {
        assertThat(config.isEnabled()).isTrue();
    }

    @Test
    void should_return15000_when_pollIntervalMsDefaultIsQueried() {
        assertThat(config.getPollIntervalMs()).isEqualTo(15000L);
    }

    @Test
    void should_returnLocalhostPort3000_when_webUiUrlDefaultIsQueried() {
        assertThat(config.getWebUiUrl()).isEqualTo("http://localhost:3000");
    }

    @Test
    void should_returnLocalhostPort9091_when_stableUrlDefaultIsQueried() {
        assertThat(config.getStableUrl()).isEqualTo("http://localhost:9091");
    }

    // --- Setter/Override tests (simulating @ConfigurationProperties injection) ---

    @Test
    void should_returnFalse_when_enabledIsSetToFalse() {
        config.setEnabled(false);
        assertThat(config.isEnabled()).isFalse();
    }

    @Test
    void should_returnCustomInterval_when_pollIntervalMsIsOverridden() {
        config.setPollIntervalMs(30000L);
        assertThat(config.getPollIntervalMs()).isEqualTo(30000L);
    }

    @Test
    void should_returnCustomUrl_when_webUiUrlIsOverridden() {
        config.setWebUiUrl("https://myapp.example.com");
        assertThat(config.getWebUiUrl()).isEqualTo("https://myapp.example.com");
    }

    @Test
    void should_returnCustomUrl_when_stableUrlIsOverridden() {
        config.setStableUrl("https://stable.example.com");
        assertThat(config.getStableUrl()).isEqualTo("https://stable.example.com");
    }

    // --- Edge cases ---

    @Test
    void should_allowZeroInterval_when_pollIntervalMsIsSetToZero() {
        config.setPollIntervalMs(0L);
        assertThat(config.getPollIntervalMs()).isZero();
    }

    @Test
    void should_allowNullWebUiUrl_when_setToNull() {
        config.setWebUiUrl(null);
        assertThat(config.getWebUiUrl()).isNull();
    }

    @Test
    void should_allowNullStableUrl_when_setToNull() {
        config.setStableUrl(null);
        assertThat(config.getStableUrl()).isNull();
    }

    @Test
    void should_allowEmptyUrl_when_webUiUrlIsSetToEmpty() {
        config.setWebUiUrl("");
        assertThat(config.getWebUiUrl()).isEmpty();
    }
}
