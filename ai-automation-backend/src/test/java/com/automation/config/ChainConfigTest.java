package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ChainConfigTest {

    private ChainConfig config;

    @BeforeEach
    void setUp() {
        config = new ChainConfig();
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(ChainConfig.class.isAnnotationPresent(Configuration.class))
                .as("ChainConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveConfigurationPropertiesAnnotation_when_classIsInspected() {
        assertThat(ChainConfig.class.isAnnotationPresent(ConfigurationProperties.class))
                .as("ChainConfig must be annotated with @ConfigurationProperties")
                .isTrue();
    }

    @Test
    void should_haveCorrectPrefix_when_configurationPropertiesIsInspected() {
        ConfigurationProperties annotation = ChainConfig.class.getAnnotation(ConfigurationProperties.class);
        assertThat(annotation.prefix()).isEqualTo("automation.chain");
    }

    // --- Default value tests ---

    @Test
    void should_return180_when_workflowTimeoutMinutesDefaultIsQueried() {
        assertThat(config.getWorkflowTimeoutMinutes()).isEqualTo(180);
    }

    // --- Setter/Override tests (simulating @ConfigurationProperties injection) ---

    @Test
    void should_returnCustomTimeout_when_workflowTimeoutMinutesIsOverridden() {
        config.setWorkflowTimeoutMinutes(60);
        assertThat(config.getWorkflowTimeoutMinutes()).isEqualTo(60);
    }

    @Test
    void should_returnLargeTimeout_when_workflowTimeoutMinutesIsSetToLargeValue() {
        config.setWorkflowTimeoutMinutes(1440);
        assertThat(config.getWorkflowTimeoutMinutes()).isEqualTo(1440);
    }

    // --- Edge cases ---

    @Test
    void should_allowZeroTimeout_when_setToZero() {
        config.setWorkflowTimeoutMinutes(0);
        assertThat(config.getWorkflowTimeoutMinutes()).isZero();
    }

    @Test
    void should_allowNegativeTimeout_when_setToNegativeValue() {
        config.setWorkflowTimeoutMinutes(-1);
        assertThat(config.getWorkflowTimeoutMinutes()).isEqualTo(-1);
    }
}
