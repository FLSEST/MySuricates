package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitConfigTest {

    private RateLimitConfig config;

    @BeforeEach
    void setUp() {
        config = new RateLimitConfig();
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(RateLimitConfig.class.isAnnotationPresent(Configuration.class))
                .as("RateLimitConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveConfigurationPropertiesAnnotation_when_classIsInspected() {
        assertThat(RateLimitConfig.class.isAnnotationPresent(ConfigurationProperties.class))
                .as("RateLimitConfig must be annotated with @ConfigurationProperties")
                .isTrue();
    }

    @Test
    void should_haveCorrectPrefix_when_configurationPropertiesIsInspected() {
        ConfigurationProperties annotation = RateLimitConfig.class.getAnnotation(ConfigurationProperties.class);
        assertThat(annotation.prefix()).isEqualTo("automation.security.rate-limit");
    }

    // --- Default value tests ---

    @Test
    void should_returnTrue_when_enabledDefaultIsQueried() {
        assertThat(config.isEnabled()).isTrue();
    }

    @Test
    void should_return120_when_requestsPerMinuteDefaultIsQueried() {
        assertThat(config.getRequestsPerMinute()).isEqualTo(120);
    }

    // --- Setter/Override tests (simulating @ConfigurationProperties injection) ---

    @Test
    void should_returnFalse_when_enabledIsSetToFalse() {
        config.setEnabled(false);
        assertThat(config.isEnabled()).isFalse();
    }

    @Test
    void should_returnCustomRate_when_requestsPerMinuteIsOverridden() {
        config.setRequestsPerMinute(60);
        assertThat(config.getRequestsPerMinute()).isEqualTo(60);
    }

    @Test
    void should_returnHighRate_when_requestsPerMinuteIsSetToLargeValue() {
        config.setRequestsPerMinute(10000);
        assertThat(config.getRequestsPerMinute()).isEqualTo(10000);
    }

    // --- Edge cases ---

    @Test
    void should_allowZeroRequestsPerMinute_when_setToZero() {
        config.setRequestsPerMinute(0);
        assertThat(config.getRequestsPerMinute()).isZero();
    }

    @Test
    void should_allowNegativeRequestsPerMinute_when_setToNegativeValue() {
        config.setRequestsPerMinute(-1);
        assertThat(config.getRequestsPerMinute()).isEqualTo(-1);
    }
}
