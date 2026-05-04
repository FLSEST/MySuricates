package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class HostPiConfigTest {

    private HostPiConfig config;

    @BeforeEach
    void setUp() {
        config = new HostPiConfig();
    }

    @Test
    void should_be_annotated_as_configuration() {
        assertThat(HostPiConfig.class.isAnnotationPresent(Configuration.class)).isTrue();
    }

    @Test
    void should_have_configuration_properties_annotation_with_correct_prefix() {
        ConfigurationProperties annotation = HostPiConfig.class.getAnnotation(ConfigurationProperties.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.prefix()).isEqualTo("automation.host-pi");
    }

    @Test
    void should_have_default_timeout_seconds_of_300() {
        assertThat(config.getTimeoutSeconds()).isEqualTo(300);
    }

    @Test
    void should_have_null_max_tokens_by_default() {
        assertThat(config.getMaxTokens()).isNull();
    }

    @Test
    void should_have_zero_inter_call_delay_by_default() {
        assertThat(config.getInterCallDelayMs()).isEqualTo(0L);
    }

    @Test
    void should_have_default_max_concurrent_calls_of_5() {
        assertThat(config.getMaxConcurrentCalls()).isEqualTo(5);
    }

    @Test
    void should_have_default_reserved_for_interactive_of_1() {
        assertThat(config.getReservedForInteractive()).isEqualTo(1);
    }

    @Test
    void should_have_default_backend_provider_anthropic() {
        assertThat(config.getDefaultBackendProvider()).isEqualTo("anthropic");
    }

    @Test
    void should_have_default_thinking_level_medium() {
        assertThat(config.getDefaultThinkingLevel()).isEqualTo("medium");
    }

    @Test
    void should_allow_overriding_timeout_seconds() {
        config.setTimeoutSeconds(600);
        assertThat(config.getTimeoutSeconds()).isEqualTo(600);
    }

    @Test
    void should_allow_setting_max_tokens() {
        config.setMaxTokens(8192);
        assertThat(config.getMaxTokens()).isEqualTo(8192);
    }

    @Test
    void should_allow_overriding_inter_call_delay_ms() {
        config.setInterCallDelayMs(1500L);
        assertThat(config.getInterCallDelayMs()).isEqualTo(1500L);
    }

    @Test
    void should_allow_overriding_max_concurrent_calls() {
        config.setMaxConcurrentCalls(10);
        assertThat(config.getMaxConcurrentCalls()).isEqualTo(10);
    }

    @Test
    void should_allow_overriding_reserved_for_interactive() {
        config.setReservedForInteractive(3);
        assertThat(config.getReservedForInteractive()).isEqualTo(3);
    }

    @Test
    void should_allow_overriding_default_backend_provider() {
        config.setDefaultBackendProvider("openai");
        assertThat(config.getDefaultBackendProvider()).isEqualTo("openai");
    }

    @Test
    void should_allow_overriding_default_thinking_level() {
        config.setDefaultThinkingLevel("high");
        assertThat(config.getDefaultThinkingLevel()).isEqualTo("high");
    }

    @Test
    void should_allow_zero_max_concurrent_calls() {
        config.setMaxConcurrentCalls(0);
        assertThat(config.getMaxConcurrentCalls()).isEqualTo(0);
    }

    @Test
    void should_allow_zero_reserved_for_interactive() {
        config.setReservedForInteractive(0);
        assertThat(config.getReservedForInteractive()).isEqualTo(0);
    }

    @Test
    void should_allow_null_max_tokens_after_setting() {
        config.setMaxTokens(4096);
        config.setMaxTokens(null);
        assertThat(config.getMaxTokens()).isNull();
    }

    @Test
    void should_allow_large_inter_call_delay() {
        config.setInterCallDelayMs(60_000L);
        assertThat(config.getInterCallDelayMs()).isEqualTo(60_000L);
    }
}
