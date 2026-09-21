package com.automation.service.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ReasoningEffortTest {

    @Test
    void cliValues_areTheLowercaseLevelsInAscendingOrder() {
        assertThat(Arrays.stream(ReasoningEffort.values()).map(ReasoningEffort::cliValue))
                .containsExactly("low", "medium", "high", "xhigh");
    }

    @Test
    void parse_isCaseInsensitiveAndTrims() {
        assertThat(ReasoningEffort.parse("High")).contains(ReasoningEffort.HIGH);
        assertThat(ReasoningEffort.parse("  xhigh\n")).contains(ReasoningEffort.XHIGH);
    }

    @Test
    void parse_roundTripsEveryCliValue() {
        for (ReasoningEffort e : ReasoningEffort.values()) {
            assertThat(ReasoningEffort.parse(e.cliValue())).contains(e);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "max", "minimal", "extra-high", "x high"})
    void parse_unsetOrUnknown_isEmptyInsteadOfThrowing(String value) {
        assertThat(ReasoningEffort.parse(value)).isEmpty();
    }
}
