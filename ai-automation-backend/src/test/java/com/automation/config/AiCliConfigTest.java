package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiCliConfigTest {

    private static final String PREFIX = "automation.ai-cli";

    private static AiCliConfig bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind(PREFIX, AiCliConfig.class)
                .orElseGet(AiCliConfig::new);
    }

    @Test
    void should_haveDocumentedDefaults() {
        AiCliConfig config = bind(Map.of());

        assertThat(config.getMaxConcurrentCalls()).isEqualTo(8);
        assertThat(config.getReservedForInteractive()).isEqualTo(1);
        assertThat(config.getCodexMaxConcurrentCalls()).isEqualTo(5);
        assertThat(config.getCodexReservedForInteractive()).isEqualTo(1);
        assertThat(config.getCodexInterCallDelayMs()).isZero();
    }

    @Test
    void should_bindAllPropertiesUnderThePrefix() {
        assertThat(AiCliConfig.class.getAnnotation(ConfigurationProperties.class).prefix()).isEqualTo(PREFIX);

        AiCliConfig config = bind(Map.of(
                PREFIX + ".max-concurrent-calls", "20",
                PREFIX + ".reserved-for-interactive", "3",
                PREFIX + ".codex-max-concurrent-calls", "2",
                PREFIX + ".codex-reserved-for-interactive", "0",
                PREFIX + ".codex-inter-call-delay-ms", "1500"));

        assertThat(config.getMaxConcurrentCalls()).isEqualTo(20);
        assertThat(config.getReservedForInteractive()).isEqualTo(3);
        assertThat(config.getCodexMaxConcurrentCalls()).isEqualTo(2);
        assertThat(config.getCodexReservedForInteractive()).isZero();
        assertThat(config.getCodexInterCallDelayMs()).isEqualTo(1500L);
    }

    @Test
    void should_keepDefaultsForUnsetProperties_when_onlySomeAreBound() {
        AiCliConfig config = bind(Map.of(PREFIX + ".codex-inter-call-delay-ms", "250"));

        assertThat(config.getCodexInterCallDelayMs()).isEqualTo(250L);
        assertThat(config.getMaxConcurrentCalls()).isEqualTo(8);
        assertThat(config.getCodexMaxConcurrentCalls()).isEqualTo(5);
    }
}
