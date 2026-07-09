package com.automation.service.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiProviderCapabilitiesTest {

    @Test
    void full_should_haveAllCapabilitiesEnabled() {
        AiProviderCapabilities caps = AiProviderCapabilities.full();

        assertThat(caps.supportsSystemPrompt()).isTrue();
        assertThat(caps.supportsModelSelection()).isTrue();
        assertThat(caps.supportsMaxTurns()).isTrue();
        assertThat(caps.supportsStreamJson()).isTrue();
        assertThat(caps.supportsAllowedTools()).isTrue();
        assertThat(caps.tokenTracking()).isTrue();
        assertThat(caps.supportsMultiTurnReasoning()).isTrue();
        assertThat(caps.supportsEffort()).isTrue();
    }

    @Test
    void minimal_should_haveAllCapabilitiesDisabled() {
        AiProviderCapabilities caps = AiProviderCapabilities.minimal();

        assertThat(caps.supportsSystemPrompt()).isFalse();
        assertThat(caps.supportsModelSelection()).isFalse();
        assertThat(caps.supportsMaxTurns()).isFalse();
        assertThat(caps.supportsStreamJson()).isFalse();
        assertThat(caps.supportsAllowedTools()).isFalse();
        assertThat(caps.tokenTracking()).isFalse();
        assertThat(caps.supportsMultiTurnReasoning()).isFalse();
        assertThat(caps.supportsEffort()).isFalse();
    }

    @Test
    void full_should_notEqualMinimal() {
        assertThat(AiProviderCapabilities.full()).isNotEqualTo(AiProviderCapabilities.minimal());
    }

    @Test
    void full_should_equalAnotherFullInstance() {
        assertThat(AiProviderCapabilities.full()).isEqualTo(AiProviderCapabilities.full());
    }

    @Test
    void minimal_should_equalAnotherMinimalInstance() {
        assertThat(AiProviderCapabilities.minimal()).isEqualTo(AiProviderCapabilities.minimal());
    }

    @Test
    void full_should_haveSameHashCodeAsAnotherFullInstance() {
        assertThat(AiProviderCapabilities.full().hashCode())
                .isEqualTo(AiProviderCapabilities.full().hashCode());
    }

    @Test
    void minimal_should_haveSameHashCodeAsAnotherMinimalInstance() {
        assertThat(AiProviderCapabilities.minimal().hashCode())
                .isEqualTo(AiProviderCapabilities.minimal().hashCode());
    }

    @Test
    void full_should_equalManuallyConstructedEquivalent() {
        var manual = new AiProviderCapabilities(true, true, true, true, true, true, true, true);

        assertThat(AiProviderCapabilities.full()).isEqualTo(manual);
    }

    @Test
    void minimal_should_equalManuallyConstructedEquivalent() {
        var manual = new AiProviderCapabilities(false, false, false, false, false, false, false, false);

        assertThat(AiProviderCapabilities.minimal()).isEqualTo(manual);
    }

    @Test
    void customCapabilities_should_differFromFullAndMinimal() {
        var custom = new AiProviderCapabilities(true, false, true, false, true, false, true, false);

        assertThat(custom).isNotEqualTo(AiProviderCapabilities.full());
        assertThat(custom).isNotEqualTo(AiProviderCapabilities.minimal());
        assertThat(custom.supportsSystemPrompt()).isTrue();
        assertThat(custom.supportsModelSelection()).isFalse();
    }

    @Test
    void toString_should_containFieldNames() {
        String str = AiProviderCapabilities.full().toString();

        assertThat(str).contains("supportsSystemPrompt=true");
        assertThat(str).contains("tokenTracking=true");
    }
}
