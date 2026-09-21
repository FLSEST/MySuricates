package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TodoGhostWatchdogConfigTest {

    /** Binds like Spring Boot does at startup: kebab-case keys below the class's own prefix. */
    private static TodoGhostWatchdogConfig bind(Map<String, String> properties) {
        String prefix = TodoGhostWatchdogConfig.class.getAnnotation(ConfigurationProperties.class).prefix();
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate(prefix, Bindable.of(TodoGhostWatchdogConfig.class));
    }

    @Test
    void should_be_a_configuration_with_prefix_automation_todo_watchdog() {
        assertThat(TodoGhostWatchdogConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
        assertThat(TodoGhostWatchdogConfig.class.getAnnotation(ConfigurationProperties.class).prefix())
                .isEqualTo("automation.todo-watchdog");
    }

    @Test
    void should_have_documented_defaults() {
        TodoGhostWatchdogConfig config = new TodoGhostWatchdogConfig();

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getStaleHours()).isEqualTo(24);
        assertThat(config.getMaxPerRun()).isEqualTo(100);
        assertThat(config.getPollMs()).isEqualTo(300_000L);
    }

    @Test
    void should_bind_every_kebab_case_property() {
        TodoGhostWatchdogConfig config = bind(Map.of(
                "automation.todo-watchdog.enabled", "false",
                "automation.todo-watchdog.stale-hours", "2",
                "automation.todo-watchdog.max-per-run", "7",
                "automation.todo-watchdog.poll-ms", "1000"));

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getStaleHours()).isEqualTo(2);
        assertThat(config.getMaxPerRun()).isEqualTo(7);
        assertThat(config.getPollMs()).isEqualTo(1000L);
    }
}
