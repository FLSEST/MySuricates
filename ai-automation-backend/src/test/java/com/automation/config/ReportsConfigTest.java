package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ReportsConfigTest {

    private ReportsConfig config;

    @BeforeEach
    void setUp() {
        config = new ReportsConfig();
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(ReportsConfig.class.isAnnotationPresent(Configuration.class))
                .as("ReportsConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveConfigurationPropertiesAnnotation_when_classIsInspected() {
        assertThat(ReportsConfig.class.isAnnotationPresent(ConfigurationProperties.class))
                .as("ReportsConfig must be annotated with @ConfigurationProperties")
                .isTrue();
    }

    @Test
    void should_haveCorrectPrefix_when_configurationPropertiesIsInspected() {
        ConfigurationProperties annotation = ReportsConfig.class.getAnnotation(ConfigurationProperties.class);
        assertThat(annotation.prefix()).isEqualTo("automation.reports");
    }

    // --- Default value tests ---

    @Test
    void should_returnRelativeReportsPath_when_directoryDefaultIsQueried() {
        assertThat(config.getDirectory()).isEqualTo("../reports");
    }

    @Test
    void should_startWithRelativePath_when_directoryDefaultIsQueried() {
        assertThat(config.getDirectory()).startsWith("..");
    }

    // --- Setter/Override tests (simulating @ConfigurationProperties injection) ---

    @Test
    void should_returnCustomDirectory_when_directoryIsOverridden() {
        config.setDirectory("/opt/myapp/reports");
        assertThat(config.getDirectory()).isEqualTo("/opt/myapp/reports");
    }

    @Test
    void should_returnAbsolutePath_when_directoryIsSetToAbsolutePath() {
        config.setDirectory("/var/reports/markdown");
        assertThat(config.getDirectory()).startsWith("/");
    }

    // --- Edge cases ---

    @Test
    void should_allowNullDirectory_when_setToNull() {
        config.setDirectory(null);
        assertThat(config.getDirectory()).isNull();
    }

    @Test
    void should_allowEmptyDirectory_when_setToEmpty() {
        config.setDirectory("");
        assertThat(config.getDirectory()).isEmpty();
    }
}
