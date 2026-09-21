package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompressionConfigTest {

    @Test
    void should_default_to60000AiInputChars() {
        assertThat(new CompressionConfig().getMaxAiInputChars()).isEqualTo(60_000);
    }

    @Test
    void should_acceptRuntimeUpdate_when_setterCalled() {
        CompressionConfig config = new CompressionConfig();
        config.setMaxAiInputChars(1234);
        assertThat(config.getMaxAiInputChars()).isEqualTo(1234);
    }

    @Test
    void should_bindKebabCaseProperty_underAutomationCompression() {
        assertThat(CompressionConfig.class.getAnnotation(ConfigurationProperties.class).prefix())
                .isEqualTo("automation.compression");

        CompressionConfig bound = new Binder(new MapConfigurationPropertySource(
                Map.of("automation.compression.max-ai-input-chars", "90000")))
                .bind("automation.compression", CompressionConfig.class)
                .get();

        assertThat(bound.getMaxAiInputChars()).isEqualTo(90_000);
    }

    @Test
    void should_keepDefault_when_noPropertiesProvided() {
        CompressionConfig bound = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bind("automation.compression", CompressionConfig.class)
                .orElseGet(CompressionConfig::new);

        assertThat(bound.getMaxAiInputChars()).isEqualTo(60_000);
    }
}
