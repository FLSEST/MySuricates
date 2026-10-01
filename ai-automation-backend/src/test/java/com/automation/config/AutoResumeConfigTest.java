package com.automation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AutoResumeConfigTest {

    private static final String PREFIX = "automation.execution.auto-resume";

    private static AutoResumeConfig bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind(PREFIX, AutoResumeConfig.class)
                .orElseGet(AutoResumeConfig::new);
    }

    @Test
    void ist_standardmaessig_an_mit_dreissig_sekunden_abstand() {
        AutoResumeConfig config = new AutoResumeConfig();

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getDelaySeconds()).isEqualTo(30);
    }

    @Test
    void behaelt_die_vorgaben_ohne_properties() {
        AutoResumeConfig config = bind(Map.of());

        assertThat(config.isEnabled()).isTrue();
        assertThat(config.getDelaySeconds()).isEqualTo(30);
    }

    @Test
    void bindet_beide_properties_unter_dem_praefix() {
        assertThat(AutoResumeConfig.class.getAnnotation(ConfigurationProperties.class).prefix())
                .isEqualTo(PREFIX);

        AutoResumeConfig config = bind(Map.of(
                PREFIX + ".enabled", "false",
                PREFIX + ".delay-seconds", "0"));

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getDelaySeconds()).isZero();
    }

    @Test
    void laesst_sich_zur_laufzeit_umstellen() {
        AutoResumeConfig config = new AutoResumeConfig();
        config.setEnabled(false);
        config.setDelaySeconds(3600);

        assertThat(config.isEnabled()).isFalse();
        assertThat(config.getDelaySeconds()).isEqualTo(3600);
    }
}
