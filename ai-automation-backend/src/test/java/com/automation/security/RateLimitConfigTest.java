package com.automation.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void should_haveValidatedAnnotation_when_classIsInspected() {
        // Without @Validated, @Min would have no effect when binding.
        assertThat(RateLimitConfig.class.isAnnotationPresent(Validated.class)).isTrue();
    }

    @Test
    void should_rejectNonPositiveClientValues_when_theyAreBound() {
        // A negative value should be noticed where it is set (startup), not only in the
        // browser client — which discards it and guesses again (37b98c97).
        assertThatThrownBy(() -> bind("client-global-backoff-seconds", "-1"))
                .hasMessageContaining("Failed to bind properties")
                .hasStackTraceContaining("clientGlobalBackoffSeconds");
        assertThatThrownBy(() -> bind("client-default-retry-after-seconds", "0"))
                .hasMessageContaining("Failed to bind properties")
                .hasStackTraceContaining("clientDefaultRetryAfterSeconds");
    }

    @Test
    void should_bindPositiveClientValues_when_theyAreConfigured() {
        RateLimitConfig bound = bind("client-global-backoff-seconds", "5");
        assertThat(bound.getClientGlobalBackoffSeconds()).isEqualTo(5);
        assertThat(bound.getClientDefaultRetryAfterSeconds()).isEqualTo(10); // unchanged default
    }

    /** Binds a single property the way Spring Boot would at startup — including validation. */
    private RateLimitConfig bind(String key, String value) {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(
                Map.of("automation.security.rate-limit." + key, value));
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return new Binder(source)
                .bind("automation.security.rate-limit",
                        Bindable.of(RateLimitConfig.class),
                        new ValidationBindHandler(validator))
                .orElseGet(RateLimitConfig::new);
    }

    @Test
    void should_return2_when_clientGlobalBackoffDefaultIsQueried() {
        // Today's client constants are the default — without configuration the
        // frontend's behavior stays unchanged (b6feccbc).
        assertThat(config.getClientGlobalBackoffSeconds()).isEqualTo(2);
    }

    @Test
    void should_return10_when_clientDefaultRetryAfterIsQueried() {
        assertThat(config.getClientDefaultRetryAfterSeconds()).isEqualTo(10);
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
