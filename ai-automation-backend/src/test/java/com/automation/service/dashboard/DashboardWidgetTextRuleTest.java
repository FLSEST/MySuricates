package com.automation.service.dashboard;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardWidgetTextRuleTest {

    @ParameterizedTest(name = "U+{0} is fatal")
    @ValueSource(ints = {
            0x0000, 0x0009, 0x000A, 0x007F, 0x0085, // ISO control
            0x200B, 0x200D, 0xFEFF, 0x202E, 0x00AD, // format: zero width, BOM, bidi override, soft hyphen
            0x2028, 0x2029,                         // line / paragraph separator
            0xD800, 0xDFFF,                         // lone surrogates
            0xE000, 0xF8FF, 0xF0000                 // private use
    })
    void fatal_code_points(int codePoint) {
        assertThat(DashboardWidgetTextRule.isFatal(codePoint)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {'a', ' ', 0x00E4, 0x00A0, 0x3164, 0x034F, 0x1F600, 0x0378})
    void ordinary_blank_or_unassigned_code_points_are_not_fatal(int codePoint) {
        assertThat(DashboardWidgetTextRule.isFatal(codePoint)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {'a', 'Z', '0', '.', 0x00E4, 0x4E2D, 0x1F600})
    void visible_code_points(int codePoint) {
        assertThat(DashboardWidgetTextRule.isVisible(codePoint)).isTrue();
    }

    @ParameterizedTest(name = "U+{0} is not visible")
    @ValueSource(ints = {
            ' ', 0x00A0, 0x2003, 0x3000,                    // spaces
            0x2800, 0x3164, 0x115F, 0x1160, 0xFFA0,         // invisible fillers
            0x034F, 0x17B4, 0x17B5, 0x180B, 0x180F,         // non-Cf default-ignorables
            0xFE00, 0xFE0F, 0xE0100, 0xE01EF,               // variation selectors
            0x0378                                          // unassigned
    })
    void invisible_code_points(int codePoint) {
        assertThat(DashboardWidgetTextRule.isVisible(codePoint)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0x17B3, 0x17B6, 0x180A, 0x034E, 0x0350})
    void neighbours_of_the_ignorable_ranges_stay_visible(int codePoint) {
        // Assigned code points right next to the explicit ranges: the ranges must not be off by one.
        assertThat(DashboardWidgetTextRule.isVisible(codePoint)).isTrue();
    }
}
