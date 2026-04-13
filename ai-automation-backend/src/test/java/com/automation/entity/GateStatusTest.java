package com.automation.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GateStatusTest {

    @Test
    void should_haveAllGateStatusValuesInDeclaredOrder() {
        assertThat(GateStatus.values())
                .containsExactly(
                        GateStatus.GREEN,
                        GateStatus.YELLOW,
                        GateStatus.RED
                );
    }

    @Test
    void should_resolveGreenViaValueOf() {
        assertThat(GateStatus.valueOf("GREEN")).isEqualTo(GateStatus.GREEN);
    }

    @Test
    void should_resolveYellowViaValueOf() {
        assertThat(GateStatus.valueOf("YELLOW")).isEqualTo(GateStatus.YELLOW);
    }

    @Test
    void should_resolveRedViaValueOf() {
        assertThat(GateStatus.valueOf("RED")).isEqualTo(GateStatus.RED);
    }

    @Test
    void should_throwException_when_valueOfReceivesInvalidName() {
        assertThatThrownBy(() -> GateStatus.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
