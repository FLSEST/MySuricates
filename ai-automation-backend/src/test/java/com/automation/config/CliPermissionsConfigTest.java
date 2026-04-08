package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CliPermissionsConfigTest {

    private CliPermissionsConfig config;

    @BeforeEach
    void setUp() {
        config = new CliPermissionsConfig();
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(CliPermissionsConfig.class.isAnnotationPresent(Configuration.class))
                .as("CliPermissionsConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveConfigurationPropertiesAnnotation_when_classIsInspected() {
        assertThat(CliPermissionsConfig.class.isAnnotationPresent(ConfigurationProperties.class))
                .as("CliPermissionsConfig must be annotated with @ConfigurationProperties")
                .isTrue();
    }

    @Test
    void should_haveCorrectPrefix_when_configurationPropertiesIsInspected() {
        ConfigurationProperties annotation = CliPermissionsConfig.class.getAnnotation(ConfigurationProperties.class);
        assertThat(annotation.prefix()).isEqualTo("automation.cli");
    }

    // --- Default value tests ---

    @Test
    void should_returnTrue_when_skipPermissionsDefaultIsQueried() {
        assertThat(config.isSkipPermissions()).isTrue();
    }

    @Test
    void should_returnEmptyList_when_allowedToolsDefaultIsQueried() {
        assertThat(config.getAllowedTools()).isEmpty();
    }

    @Test
    void should_returnNonNullList_when_allowedToolsDefaultIsQueried() {
        assertThat(config.getAllowedTools()).isNotNull();
    }

    // --- Setter/Override tests (simulating @ConfigurationProperties injection) ---

    @Test
    void should_returnFalse_when_skipPermissionsIsSetToFalse() {
        config.setSkipPermissions(false);
        assertThat(config.isSkipPermissions()).isFalse();
    }

    @Test
    void should_returnCustomTools_when_allowedToolsIsOverridden() {
        config.setAllowedTools(List.of("bash", "read", "write"));
        assertThat(config.getAllowedTools()).containsExactly("bash", "read", "write");
    }

    @Test
    void should_returnSingleTool_when_allowedToolsIsSetToSingleElement() {
        config.setAllowedTools(List.of("bash"));
        assertThat(config.getAllowedTools()).hasSize(1).containsExactly("bash");
    }

    // --- Edge cases ---

    @Test
    void should_allowNullAllowedTools_when_setToNull() {
        config.setAllowedTools(null);
        assertThat(config.getAllowedTools()).isNull();
    }

    @Test
    void should_allowEmptyList_when_allowedToolsIsSetToEmptyList() {
        config.setAllowedTools(List.of());
        assertThat(config.getAllowedTools()).isEmpty();
    }

    @Test
    void should_preserveOrder_when_allowedToolsIsSetWithMultipleElements() {
        config.setAllowedTools(List.of("write", "bash", "read"));
        assertThat(config.getAllowedTools()).containsExactly("write", "bash", "read");
    }
}
