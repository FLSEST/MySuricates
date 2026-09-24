package com.automation.util;

import com.automation.entity.RunStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TODO b22c4db0: one parser for the enum filters of the list endpoints, after 4e7a7401 (/todos). */
class EnumParameterTest {

    private enum Single { ONLY }

    private enum Pair { LEFT, RIGHT }

    @Test
    void parse_isTrimmedAndCaseInsensitive() {
        assertThat(EnumParameter.parse(RunStatus.class, "status", " completed_with_errors ")).isEqualTo(RunStatus.COMPLETED_WITH_ERRORS);
        assertThat(EnumParameter.parse(Pair.class, "side", "Left")).isEqualTo(Pair.LEFT);
    }

    @Test
    void parseOptional_missingOrBlankValue_meansNoFilter() {
        assertThat(EnumParameter.parseOptional(RunStatus.class, "status", null)).isNull();
        assertThat(EnumParameter.parseOptional(RunStatus.class, "status", "  ")).isNull();
        assertThat(EnumParameter.parseOptional(RunStatus.class, "status", "failed")).isEqualTo(RunStatus.FAILED);
    }

    @Test
    void unknownValue_namesTheParameterAndTheAllowedValuesButNoClassName() {
        assertThatThrownBy(() -> EnumParameter.parseOptional(RunStatus.class, "status", "FOO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("status muss PENDING, RUNNING, PAUSED, COMPLETED, COMPLETED_WITH_ERRORS, FAILED, "
                        + "CANCELLED oder INTERRUPTED sein, nicht 'FOO'")
                .message().doesNotContain("com.automation", "No enum constant");
    }

    @Test
    void unknownValue_keepsTheValueAsSent() {
        assertThatThrownBy(() -> EnumParameter.parse(Pair.class, "side", " up "))
                .hasMessage("side muss LEFT oder RIGHT sein, nicht ' up '");
    }

    @Test
    void unknownValue_ofASingleConstantEnum_readsAsASentence() {
        assertThatThrownBy(() -> EnumParameter.parse(Single.class, "mode", "other"))
                .hasMessage("mode muss ONLY sein, nicht 'other'");
    }
}
