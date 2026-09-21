package com.automation.service.live;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class LiveLineKindTest {

    @Test
    void wire_isTheLowercaseName_theFrontendStylesBy() {
        assertThat(LiveLineKind.LINE.wire()).isEqualTo("line");
        assertThat(LiveLineKind.TOOL_RESULT.wire()).isEqualTo("tool_result");
        assertThat(LiveLineKind.TOOL_ERROR.wire()).isEqualTo("tool_error");
    }

    @Test
    void wire_isLocaleIndependent() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));
            // Turkish lower-casing would turn the I in LINE into a dotless ı
            assertThat(LiveLineKind.LINE.wire()).isEqualTo("line");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void wireNames_areUnique() {
        assertThat(Arrays.stream(LiveLineKind.values()).map(LiveLineKind::wire))
                .doesNotHaveDuplicates()
                .containsExactly("line", "text", "tool", "tool_result", "tool_error", "result", "system");
    }
}
