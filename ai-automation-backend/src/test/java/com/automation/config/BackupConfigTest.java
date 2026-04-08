package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class BackupConfigTest {

    private BackupConfig config;

    @BeforeEach
    void setUp() {
        config = new BackupConfig();
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(BackupConfig.class.isAnnotationPresent(Configuration.class))
                .as("BackupConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveConfigurationPropertiesAnnotation_when_classIsInspected() {
        assertThat(BackupConfig.class.isAnnotationPresent(ConfigurationProperties.class))
                .as("BackupConfig must be annotated with @ConfigurationProperties")
                .isTrue();
    }

    @Test
    void should_haveCorrectPrefix_when_configurationPropertiesIsInspected() {
        ConfigurationProperties annotation = BackupConfig.class.getAnnotation(ConfigurationProperties.class);
        assertThat(annotation.prefix()).isEqualTo("automation.backup");
    }

    // --- Default value tests ---

    @Test
    void should_returnTrue_when_enabledDefaultIsQueried() {
        assertThat(config.isEnabled()).isTrue();
    }

    @Test
    void should_returnCorrectCron_when_cronDefaultIsQueried() {
        assertThat(config.getCron()).isEqualTo("0 0 2 * * *");
    }

    @Test
    void should_return30_when_retainCountDefaultIsQueried() {
        assertThat(config.getRetainCount()).isEqualTo(30);
    }

    @Test
    void should_returnUserHomeBasedPath_when_directoryDefaultIsQueried() {
        String expectedDir = System.getProperty("user.home") + "/.autotrigger/backups";
        assertThat(config.getDirectory()).isEqualTo(expectedDir);
    }

    @Test
    void should_endWithAutotriggerBackups_when_directoryDefaultIsQueried() {
        assertThat(config.getDirectory()).endsWith("/.autotrigger/backups");
    }

    // --- Setter/Override tests (simulating @ConfigurationProperties injection) ---

    @Test
    void should_returnFalse_when_enabledIsSetToFalse() {
        config.setEnabled(false);
        assertThat(config.isEnabled()).isFalse();
    }

    @Test
    void should_returnCustomCron_when_cronIsOverridden() {
        config.setCron("0 0 3 * * *");
        assertThat(config.getCron()).isEqualTo("0 0 3 * * *");
    }

    @Test
    void should_returnCustomRetainCount_when_retainCountIsOverridden() {
        config.setRetainCount(7);
        assertThat(config.getRetainCount()).isEqualTo(7);
    }

    @Test
    void should_returnCustomDirectory_when_directoryIsOverridden() {
        config.setDirectory("/custom/backup/path");
        assertThat(config.getDirectory()).isEqualTo("/custom/backup/path");
    }

    // --- Edge cases ---

    @Test
    void should_allowZeroRetainCount_when_setToZero() {
        config.setRetainCount(0);
        assertThat(config.getRetainCount()).isZero();
    }

    @Test
    void should_allowNullDirectory_when_setToNull() {
        config.setDirectory(null);
        assertThat(config.getDirectory()).isNull();
    }

    @Test
    void should_allowEmptyCron_when_setToEmpty() {
        config.setCron("");
        assertThat(config.getCron()).isEmpty();
    }
}
