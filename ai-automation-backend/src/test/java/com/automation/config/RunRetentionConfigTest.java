package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RunRetentionConfigTest {

    /** Binds like Spring Boot does at startup: kebab-case keys below the class's own prefix. */
    private static RunRetentionConfig bind(Map<String, String> properties) {
        String prefix = RunRetentionConfig.class.getAnnotation(ConfigurationProperties.class).prefix();
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate(prefix, Bindable.of(RunRetentionConfig.class));
    }

    @Test
    void should_be_a_configuration_with_prefix_automation_run_retention() {
        assertThat(RunRetentionConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
        assertThat(RunRetentionConfig.class.getAnnotation(ConfigurationProperties.class).prefix())
                .isEqualTo("automation.run.retention");
    }

    @Test
    void should_have_documented_defaults() {
        RunRetentionConfig config = new RunRetentionConfig();

        assertThat(config.getRetentionDays()).isEqualTo(90);
        assertThat(config.getMaxPerNight()).isEqualTo(200);
    }

    @Test
    void should_bind_properties_below_the_dotted_prefix() {
        RunRetentionConfig config = bind(Map.of(
                "automation.run.retention.retention-days", "0",
                "automation.run.retention.max-per-night", "25"));

        assertThat(config.getRetentionDays()).as("0 disables the job").isZero();
        assertThat(config.getMaxPerNight()).isEqualTo(25);
    }
}
