package com.automation.service.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PiThinkingLevelTest {

    private static final String DEFAULT = "minimal";

    @ParameterizedTest(name = "effort {0} -> {1}")
    @CsvSource({"low,low", "medium,medium", "high,high", "xhigh,xhigh", "HIGH,high", "' Medium ',medium"})
    void should_useExplicitEffort_overMaxTurnsAndDefault(String effort, String expected) {
        assertThat(PiThinkingLevel.forReasoning(1, effort, DEFAULT)).isEqualTo(expected);
        assertThat(PiThinkingLevel.forReasoning(10, effort, DEFAULT)).isEqualTo(expected);
        assertThat(PiThinkingLevel.forReasoning(null, effort, DEFAULT)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "maxTurns {0} -> {1}")
    @CsvSource({"-5,low", "0,low", "1,low", "2,medium", "3,medium", "4,high", "100,high"})
    void should_mapMaxTurns_when_noEffortGiven(int maxTurns, String expected) {
        assertThat(PiThinkingLevel.forReasoning(maxTurns, null, DEFAULT)).isEqualTo(expected);
    }

    @Test
    void should_fallBackToConfiguredDefault_when_neitherEffortNorMaxTurnsGiven() {
        assertThat(PiThinkingLevel.forReasoning(null, null, DEFAULT)).isEqualTo(DEFAULT);
        assertThat(PiThinkingLevel.forReasoning(null, null, null)).isNull();
    }

    @ParameterizedTest(name = "unusable effort ''{0}'' falls through")
    @ValueSource(strings = {"", "   ", "turbo", "max"})
    void should_ignoreBlankOrUnknownEffort_andUseMaxTurns(String effort) {
        assertThat(PiThinkingLevel.forReasoning(3, effort, DEFAULT)).isEqualTo("medium");
        assertThat(PiThinkingLevel.forReasoning(null, effort, DEFAULT)).isEqualTo(DEFAULT);
    }
}
