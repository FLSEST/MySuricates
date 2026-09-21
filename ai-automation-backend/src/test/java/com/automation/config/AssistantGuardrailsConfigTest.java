package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantGuardrailsConfigTest {

    /** Binds like Spring Boot does at startup: kebab-case keys below the class's own prefix. */
    private static AssistantGuardrailsConfig bind(Map<String, String> properties) {
        String prefix = AssistantGuardrailsConfig.class.getAnnotation(ConfigurationProperties.class).prefix();
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate(prefix, Bindable.of(AssistantGuardrailsConfig.class));
    }

    @Test
    void should_be_a_configuration_with_prefix_automation_assistant_guardrails() {
        assertThat(AssistantGuardrailsConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
        assertThat(AssistantGuardrailsConfig.class.getAnnotation(ConfigurationProperties.class).prefix())
                .isEqualTo("automation.assistant.guardrails");
    }

    @Test
    void should_default_to_30_actions_per_hour() {
        assertThat(new AssistantGuardrailsConfig().getMaxActionsPerHour()).isEqualTo(30);
    }

    @Test
    void should_bind_max_actions_per_hour() {
        AssistantGuardrailsConfig config = bind(Map.of("automation.assistant.guardrails.max-actions-per-hour", "3"));

        assertThat(config.getMaxActionsPerHour()).isEqualTo(3);
    }

    @Test
    void should_keep_default_when_nothing_is_configured() {
        assertThat(bind(Map.of()).getMaxActionsPerHour()).isEqualTo(30);
    }
}
