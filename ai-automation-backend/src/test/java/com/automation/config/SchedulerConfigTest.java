package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerConfigTest {

    private SchedulerConfig config;

    @BeforeEach
    void setUp() {
        config = new SchedulerConfig();
    }

    // --- Defaults ---

    @Test
    void should_haveEmptySkipUntil_when_defaultConstructed() {
        assertThat(config.getSkipUntil()).isEmpty();
    }

    @Test
    void should_haveFiveResumeGraceMinutes_when_defaultConstructed() {
        assertThat(config.getResumeGraceMinutes()).isEqualTo(5L);
    }

    @Test
    void should_haveCatchUpOnResumeEnabled_when_defaultConstructed() {
        assertThat(config.isCatchUpOnResume()).isTrue();
    }

    // --- Setter/Getter ---

    @Test
    void should_updateSkipUntil_when_setterCalled() {
        config.setSkipUntil("2026-07-03T12:00:00");
        assertThat(config.getSkipUntil()).isEqualTo("2026-07-03T12:00:00");
    }

    @Test
    void should_acceptNullSkipUntil_when_setterCalledWithNull() {
        config.setSkipUntil(null);
        assertThat(config.getSkipUntil()).isNull();
    }

    @Test
    void should_updateResumeGraceMinutes_when_setterCalled() {
        config.setResumeGraceMinutes(15L);
        assertThat(config.getResumeGraceMinutes()).isEqualTo(15L);
    }

    @Test
    void should_allowZeroResumeGraceMinutes_when_resumeDetectionDisabled() {
        config.setResumeGraceMinutes(0L);
        assertThat(config.getResumeGraceMinutes()).isZero();
    }

    @Test
    void should_disableCatchUpOnResume_when_setterCalledWithFalse() {
        config.setCatchUpOnResume(false);
        assertThat(config.isCatchUpOnResume()).isFalse();
    }

    // --- ConfigurationProperties-Binding ---

    @Test
    void should_bindAllProperties_when_boundFromConfigurationSource() {
        Map<String, String> props = Map.of(
                "automation.scheduler.skip-until", "2026-12-31T23:59:59",
                "automation.scheduler.resume-grace-minutes", "10",
                "automation.scheduler.catch-up-on-resume", "false");

        SchedulerConfig bound = new Binder(new MapConfigurationPropertySource(props))
                .bind("automation.scheduler", SchedulerConfig.class)
                .get();

        assertThat(bound.getSkipUntil()).isEqualTo("2026-12-31T23:59:59");
        assertThat(bound.getResumeGraceMinutes()).isEqualTo(10L);
        assertThat(bound.isCatchUpOnResume()).isFalse();
    }

    @Test
    void should_keepDefaults_when_noPropertiesProvided() {
        SchedulerConfig bound = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bind("automation.scheduler", SchedulerConfig.class)
                .orElseGet(SchedulerConfig::new);

        assertThat(bound.getSkipUntil()).isEmpty();
        assertThat(bound.getResumeGraceMinutes()).isEqualTo(5L);
        assertThat(bound.isCatchUpOnResume()).isTrue();
    }
}
