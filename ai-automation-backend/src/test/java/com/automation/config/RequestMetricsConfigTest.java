package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestMetricsConfigTest {

    /** Binds like Spring Boot does at startup: kebab-case keys below the class's own prefix. */
    private static RequestMetricsConfig bind(Map<String, String> properties) {
        String prefix = RequestMetricsConfig.class.getAnnotation(ConfigurationProperties.class).prefix();
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate(prefix, Bindable.of(RequestMetricsConfig.class));
    }

    @Test
    void should_be_a_configuration_with_prefix_automation_request_metrics() {
        assertThat(RequestMetricsConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
        assertThat(RequestMetricsConfig.class.getAnnotation(ConfigurationProperties.class).prefix())
                .isEqualTo("automation.request-metrics");
    }

    @Test
    void should_have_documented_defaults() {
        RequestMetricsConfig config = new RequestMetricsConfig();

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getRetentionDays()).isEqualTo(90);
        assertThat(config.getSlowRequestMs()).isEqualTo(2000L);
        // on by default: the dev instance runs without any profile, see class comment
        assertThat(config.isServerTimingEnabled()).isTrue();
        assertThat(config.isDriftDetectionEnabled()).isTrue();
        assertThat(config.getDriftFactor()).isEqualTo(2.0);
        assertThat(config.getDriftMinCalls()).isEqualTo(20);
        assertThat(config.getBaselineDays()).isEqualTo(7);
    }

    @Test
    void should_bind_every_kebab_case_property() {
        RequestMetricsConfig config = bind(Map.of(
                "automation.request-metrics.enabled", "false",
                "automation.request-metrics.retention-days", "0",
                "automation.request-metrics.slow-request-ms", "500",
                "automation.request-metrics.server-timing-enabled", "false",
                "automation.request-metrics.drift-detection-enabled", "false",
                "automation.request-metrics.drift-factor", "1.5",
                "automation.request-metrics.drift-min-calls", "5",
                "automation.request-metrics.baseline-days", "14"));

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getRetentionDays()).isZero();
        assertThat(config.getSlowRequestMs()).isEqualTo(500L);
        assertThat(config.isServerTimingEnabled()).isFalse();
        assertThat(config.isDriftDetectionEnabled()).isFalse();
        assertThat(config.getDriftFactor()).isEqualTo(1.5);
        assertThat(config.getDriftMinCalls()).isEqualTo(5);
        assertThat(config.getBaselineDays()).isEqualTo(14);
    }

    @Test
    void should_keep_defaults_for_unset_properties() {
        RequestMetricsConfig config = bind(Map.of("automation.request-metrics.drift-factor", "3"));

        assertThat(config.getDriftFactor()).isEqualTo(3.0);
        assertThat(config.getRetentionDays()).isEqualTo(90);
        assertThat(config.isServerTimingEnabled()).isTrue();
    }
}
