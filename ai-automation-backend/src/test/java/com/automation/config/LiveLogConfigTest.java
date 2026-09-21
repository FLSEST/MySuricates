package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LiveLogConfigTest {

    /** Binds like Spring Boot does at startup: kebab-case keys below the class's own prefix. */
    private static LiveLogConfig bind(Map<String, String> properties) {
        String prefix = LiveLogConfig.class.getAnnotation(ConfigurationProperties.class).prefix();
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate(prefix, Bindable.of(LiveLogConfig.class));
    }

    @Test
    void should_be_a_configuration_with_prefix_automation_live_log() {
        assertThat(LiveLogConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
        assertThat(LiveLogConfig.class.getAnnotation(ConfigurationProperties.class).prefix())
                .isEqualTo("automation.live-log");
    }

    @Test
    void should_have_documented_defaults() {
        LiveLogConfig config = new LiveLogConfig();

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getMaxLineLength()).isEqualTo(2000);
        assertThat(config.getBufferLines()).isEqualTo(1000);
        assertThat(config.getFlushIntervalMs()).isEqualTo(250L);
        assertThat(config.getMaxLinesPerFlush()).isEqualTo(200);
        assertThat(config.getMaxCharsPerFlush()).isEqualTo(32_000);
        assertThat(config.getRetentionMinutes()).isEqualTo(10);
        assertThat(config.getMaxBufferedRuns()).isEqualTo(20);
    }

    @Test
    void should_keep_one_flush_below_the_replay_buffer() {
        // a single batch larger than the replay buffer would send lines a reload can no longer show
        LiveLogConfig config = new LiveLogConfig();
        assertThat(config.getMaxLinesPerFlush()).isLessThanOrEqualTo(config.getBufferLines());
    }

    @Test
    void should_bind_every_kebab_case_property() {
        LiveLogConfig config = bind(Map.of(
                "automation.live-log.enabled", "false",
                "automation.live-log.max-line-length", "80",
                "automation.live-log.buffer-lines", "10",
                "automation.live-log.flush-interval-ms", "1000",
                "automation.live-log.max-lines-per-flush", "5",
                "automation.live-log.max-chars-per-flush", "400",
                "automation.live-log.retention-minutes", "1",
                "automation.live-log.max-buffered-runs", "2"));

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getMaxLineLength()).isEqualTo(80);
        assertThat(config.getBufferLines()).isEqualTo(10);
        assertThat(config.getFlushIntervalMs()).isEqualTo(1000L);
        assertThat(config.getMaxLinesPerFlush()).isEqualTo(5);
        assertThat(config.getMaxCharsPerFlush()).isEqualTo(400);
        assertThat(config.getRetentionMinutes()).isEqualTo(1);
        assertThat(config.getMaxBufferedRuns()).isEqualTo(2);
    }
}
