package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class WishConfigTest {

    private WishConfig config;

    @BeforeEach
    void setUp() {
        config = new WishConfig();
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(WishConfig.class.isAnnotationPresent(Configuration.class))
                .as("WishConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveConfigurationPropertiesAnnotation_when_classIsInspected() {
        assertThat(WishConfig.class.isAnnotationPresent(ConfigurationProperties.class))
                .as("WishConfig must be annotated with @ConfigurationProperties")
                .isTrue();
    }

    @Test
    void should_haveCorrectPrefix_when_configurationPropertiesIsInspected() {
        ConfigurationProperties annotation = WishConfig.class.getAnnotation(ConfigurationProperties.class);
        assertThat(annotation.prefix()).isEqualTo("automation.wish");
    }

    // --- Default value tests ---

    @Test
    void should_return120_when_totalTimeoutMinutesDefaultIsQueried() {
        assertThat(config.getTotalTimeoutMinutes()).isEqualTo(120);
    }

    @Test
    void should_return30_when_workflowWaitMinutesDefaultIsQueried() {
        assertThat(config.getWorkflowWaitMinutes()).isEqualTo(30);
    }

    @Test
    void should_returnTrue_when_watchdogEnabledDefaultIsQueried() {
        assertThat(config.isWatchdogEnabled()).isTrue();
    }

    @Test
    void should_return120000_when_watchdogIntervalMsDefaultIsQueried() {
        assertThat(config.getWatchdogIntervalMs()).isEqualTo(120_000L);
    }

    @Test
    void should_return45_when_watchdogStaleMinutesDefaultIsQueried() {
        assertThat(config.getWatchdogStaleMinutes()).isEqualTo(45);
    }

    @Test
    void should_return600_when_defaultShellTimeoutSecondsDefaultIsQueried() {
        assertThat(config.getDefaultShellTimeoutSeconds()).isEqualTo(600);
    }

    @Test
    void should_return900_when_defaultAgenticTimeoutSecondsDefaultIsQueried() {
        assertThat(config.getDefaultAgenticTimeoutSeconds()).isEqualTo(900);
    }

    @Test
    void should_return900_when_defaultAgenticIterationTimeoutSecondsDefaultIsQueried() {
        assertThat(config.getDefaultAgenticIterationTimeoutSeconds()).isEqualTo(900);
    }

    @Test
    void should_return3600_when_defaultAgenticTotalTimeoutSecondsDefaultIsQueried() {
        assertThat(config.getDefaultAgenticTotalTimeoutSeconds()).isEqualTo(3600);
    }

    // --- Setter/Override tests (simulating @ConfigurationProperties injection) ---

    @Test
    void should_returnCustomValue_when_totalTimeoutMinutesIsOverridden() {
        config.setTotalTimeoutMinutes(240);
        assertThat(config.getTotalTimeoutMinutes()).isEqualTo(240);
    }

    @Test
    void should_returnCustomValue_when_workflowWaitMinutesIsOverridden() {
        config.setWorkflowWaitMinutes(60);
        assertThat(config.getWorkflowWaitMinutes()).isEqualTo(60);
    }

    @Test
    void should_returnFalse_when_watchdogEnabledIsSetToFalse() {
        config.setWatchdogEnabled(false);
        assertThat(config.isWatchdogEnabled()).isFalse();
    }

    @Test
    void should_returnCustomValue_when_watchdogIntervalMsIsOverridden() {
        config.setWatchdogIntervalMs(60_000L);
        assertThat(config.getWatchdogIntervalMs()).isEqualTo(60_000L);
    }

    @Test
    void should_returnCustomValue_when_watchdogStaleMinutesIsOverridden() {
        config.setWatchdogStaleMinutes(90);
        assertThat(config.getWatchdogStaleMinutes()).isEqualTo(90);
    }

    @Test
    void should_returnCustomValue_when_defaultShellTimeoutSecondsIsOverridden() {
        config.setDefaultShellTimeoutSeconds(1200);
        assertThat(config.getDefaultShellTimeoutSeconds()).isEqualTo(1200);
    }

    @Test
    void should_returnCustomValue_when_defaultAgenticTimeoutSecondsIsOverridden() {
        config.setDefaultAgenticTimeoutSeconds(1800);
        assertThat(config.getDefaultAgenticTimeoutSeconds()).isEqualTo(1800);
    }

    @Test
    void should_returnCustomValue_when_defaultAgenticIterationTimeoutSecondsIsOverridden() {
        config.setDefaultAgenticIterationTimeoutSeconds(1800);
        assertThat(config.getDefaultAgenticIterationTimeoutSeconds()).isEqualTo(1800);
    }

    @Test
    void should_returnCustomValue_when_defaultAgenticTotalTimeoutSecondsIsOverridden() {
        config.setDefaultAgenticTotalTimeoutSeconds(7200);
        assertThat(config.getDefaultAgenticTotalTimeoutSeconds()).isEqualTo(7200);
    }

    // --- Edge cases ---

    @Test
    void should_allowZeroTimeout_when_totalTimeoutMinutesSetToZero() {
        config.setTotalTimeoutMinutes(0);
        assertThat(config.getTotalTimeoutMinutes()).isZero();
    }

    @Test
    void should_allowZeroWait_when_workflowWaitMinutesSetToZero() {
        config.setWorkflowWaitMinutes(0);
        assertThat(config.getWorkflowWaitMinutes()).isZero();
    }

    @Test
    void should_allowZeroInterval_when_watchdogIntervalMsSetToZero() {
        config.setWatchdogIntervalMs(0L);
        assertThat(config.getWatchdogIntervalMs()).isZero();
    }

    @Test
    void should_allowZeroStaleMinutes_when_watchdogStaleMinutesSetToZero() {
        config.setWatchdogStaleMinutes(0);
        assertThat(config.getWatchdogStaleMinutes()).isZero();
    }

    @Test
    void should_allowZeroShellTimeout_when_defaultShellTimeoutSecondsSetToZero() {
        config.setDefaultShellTimeoutSeconds(0);
        assertThat(config.getDefaultShellTimeoutSeconds()).isZero();
    }

    @Test
    void should_allowLargeInterval_when_watchdogIntervalMsSetToHighValue() {
        config.setWatchdogIntervalMs(3_600_000L);
        assertThat(config.getWatchdogIntervalMs()).isEqualTo(3_600_000L);
    }
}
