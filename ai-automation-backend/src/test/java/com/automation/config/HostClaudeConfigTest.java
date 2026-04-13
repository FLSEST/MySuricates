package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class HostClaudeConfigTest {

    private HostClaudeConfig config;

    @BeforeEach
    void setUp() {
        config = new HostClaudeConfig();
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(HostClaudeConfig.class.isAnnotationPresent(Configuration.class))
                .as("HostClaudeConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveConfigurationPropertiesAnnotation_when_classIsInspected() {
        assertThat(HostClaudeConfig.class.isAnnotationPresent(ConfigurationProperties.class))
                .as("HostClaudeConfig must be annotated with @ConfigurationProperties")
                .isTrue();
    }

    @Test
    void should_haveCorrectPrefix_when_configurationPropertiesIsInspected() {
        ConfigurationProperties annotation = HostClaudeConfig.class.getAnnotation(ConfigurationProperties.class);
        assertThat(annotation.prefix()).isEqualTo("automation.host-claude");
    }

    // --- Default value tests ---

    @Test
    void should_return300_when_timeoutSecondsDefaultIsQueried() {
        assertThat(config.getTimeoutSeconds()).isEqualTo(300);
    }

    @Test
    void should_returnNull_when_maxTokensDefaultIsQueried() {
        assertThat(config.getMaxTokens()).isNull();
    }

    @Test
    void should_returnZero_when_interCallDelayMsDefaultIsQueried() {
        assertThat(config.getInterCallDelayMs()).isZero();
    }

    @Test
    void should_return5_when_maxConcurrentCallsDefaultIsQueried() {
        assertThat(config.getMaxConcurrentCalls()).isEqualTo(5);
    }

    @Test
    void should_return1_when_reservedForInteractiveDefaultIsQueried() {
        assertThat(config.getReservedForInteractive()).isEqualTo(1);
    }

    // --- Setter/Override tests (simulating @ConfigurationProperties injection) ---

    @Test
    void should_returnCustomTimeout_when_timeoutSecondsIsOverridden() {
        config.setTimeoutSeconds(600);
        assertThat(config.getTimeoutSeconds()).isEqualTo(600);
    }

    @Test
    void should_returnCustomMaxTokens_when_maxTokensIsOverridden() {
        config.setMaxTokens(4096);
        assertThat(config.getMaxTokens()).isEqualTo(4096);
    }

    @Test
    void should_returnCustomDelay_when_interCallDelayMsIsOverridden() {
        config.setInterCallDelayMs(500L);
        assertThat(config.getInterCallDelayMs()).isEqualTo(500L);
    }

    @Test
    void should_returnCustomConcurrency_when_maxConcurrentCallsIsOverridden() {
        config.setMaxConcurrentCalls(10);
        assertThat(config.getMaxConcurrentCalls()).isEqualTo(10);
    }

    @Test
    void should_returnCustomReserved_when_reservedForInteractiveIsOverridden() {
        config.setReservedForInteractive(3);
        assertThat(config.getReservedForInteractive()).isEqualTo(3);
    }

    // --- Edge cases ---

    @Test
    void should_allowZeroTimeout_when_setToZero() {
        config.setTimeoutSeconds(0);
        assertThat(config.getTimeoutSeconds()).isZero();
    }

    @Test
    void should_allowNullMaxTokens_when_explicitlySetToNull() {
        config.setMaxTokens(1000);
        config.setMaxTokens(null);
        assertThat(config.getMaxTokens()).isNull();
    }

    @Test
    void should_allowZeroConcurrency_when_setToZero() {
        config.setMaxConcurrentCalls(0);
        assertThat(config.getMaxConcurrentCalls()).isZero();
    }

    @Test
    void should_allowZeroReserved_when_setToZero() {
        config.setReservedForInteractive(0);
        assertThat(config.getReservedForInteractive()).isZero();
    }

    @Test
    void should_allowLargeDelay_when_setToHighValue() {
        config.setInterCallDelayMs(60_000L);
        assertThat(config.getInterCallDelayMs()).isEqualTo(60_000L);
    }
}
