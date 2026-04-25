package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AiProviderConfig")
class AiProviderConfigTest {

    private AiProviderConfig config;

    @BeforeEach
    void setUp() {
        config = new AiProviderConfig();
    }

    @Test
    @DisplayName("should_returnClaude_when_defaultProviderNotChanged")
    void should_returnClaude_when_defaultProviderNotChanged() {
        assertThat(config.getDefaultProvider()).isEqualTo("CLAUDE");
    }

    @Test
    @DisplayName("should_updateProvider_when_setterCalledWithCodex")
    void should_updateProvider_when_setterCalledWithCodex() {
        config.setDefaultProvider("CODEX");
        assertThat(config.getDefaultProvider()).isEqualTo("CODEX");
    }

    @Test
    @DisplayName("should_updateProvider_when_setterCalledWithClaude")
    void should_updateProvider_when_setterCalledWithClaude() {
        config.setDefaultProvider("CLAUDE");
        assertThat(config.getDefaultProvider()).isEqualTo("CLAUDE");
    }

    @Test
    @DisplayName("should_acceptNull_when_setterCalledWithNull")
    void should_acceptNull_when_setterCalledWithNull() {
        config.setDefaultProvider(null);
        assertThat(config.getDefaultProvider()).isNull();
    }

    @Test
    @DisplayName("should_acceptEmptyString_when_setterCalledWithEmpty")
    void should_acceptEmptyString_when_setterCalledWithEmpty() {
        config.setDefaultProvider("");
        assertThat(config.getDefaultProvider()).isEmpty();
    }

    @Test
    @DisplayName("should_acceptArbitraryProviderName_when_setterCalledWithCustom")
    void should_acceptArbitraryProviderName_when_setterCalledWithCustom() {
        config.setDefaultProvider("GEMINI");
        assertThat(config.getDefaultProvider()).isEqualTo("GEMINI");
    }

    @Test
    @DisplayName("should_overrideValue_when_setterCalledMultipleTimes")
    void should_overrideValue_when_setterCalledMultipleTimes() {
        config.setDefaultProvider("CODEX");
        config.setDefaultProvider("CLAUDE");
        assertThat(config.getDefaultProvider()).isEqualTo("CLAUDE");
    }

    @Test
    @DisplayName("should_beAnnotatedAsConfiguration_when_classInspected")
    void should_beAnnotatedAsConfiguration_when_classInspected() {
        assertThat(AiProviderConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
    }

    @Test
    @DisplayName("should_beAnnotatedAsConfigurationProperties_when_classInspected")
    void should_beAnnotatedAsConfigurationProperties_when_classInspected() {
        assertThat(AiProviderConfig.class.isAnnotationPresent(ConfigurationProperties.class)).isTrue();
    }

    @Test
    @DisplayName("should_useAutomationAiPrefix_when_configurationPropertiesInspected")
    void should_useAutomationAiPrefix_when_configurationPropertiesInspected() {
        ConfigurationProperties annotation = AiProviderConfig.class.getAnnotation(ConfigurationProperties.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.prefix()).isEqualTo("automation.ai");
    }
}
