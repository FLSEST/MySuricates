package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TodoTriggerConfigTest {

    /** Binds like Spring Boot does at startup: kebab-case keys below the class's own prefix. */
    private static TodoTriggerConfig bind(Map<String, String> properties) {
        String prefix = TodoTriggerConfig.class.getAnnotation(ConfigurationProperties.class).prefix();
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate(prefix, Bindable.of(TodoTriggerConfig.class));
    }

    @Test
    void should_be_a_configuration_with_prefix_automation_todo_trigger() {
        assertThat(TodoTriggerConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
        assertThat(TodoTriggerConfig.class.getAnnotation(ConfigurationProperties.class).prefix())
                .isEqualTo("automation.todo-trigger");
    }

    @Test
    void should_default_to_no_pause_window() {
        assertThat(new TodoTriggerConfig().getSkipUntil()).isEmpty();
    }

    @Test
    void should_bind_skip_until_as_iso_instant_string() {
        TodoTriggerConfig config = bind(Map.of("automation.todo-trigger.skip-until", "2026-08-17T22:00:00Z"));

        assertThat(config.getSkipUntil()).isEqualTo("2026-08-17T22:00:00Z");
    }

    @Test
    void should_ignore_poll_interval_which_is_read_by_the_scheduled_annotation() {
        // poll-interval-ms lives under the same prefix but has no field here; binding must not fail
        TodoTriggerConfig config = bind(Map.of("automation.todo-trigger.poll-interval-ms", "60000"));

        assertThat(config.getSkipUntil()).isEmpty();
    }
}
