package com.automation.service.system;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionSettingKeysTest {

    @Test
    void resolve_canonicalKey_returnsItself() {
        assertThat(ExecutionSettingKeys.resolve("claude-timeout-seconds")).isEqualTo("claude-timeout-seconds");
    }

    @Test
    void resolve_camelCaseAlias_returnsCanonicalKey() {
        assertThat(ExecutionSettingKeys.resolve("claudeTimeoutSeconds")).isEqualTo("claude-timeout-seconds");
    }

    @Test
    void resolve_unknownKey_throwsWithOriginalKey() {
        assertThatThrownBy(() -> ExecutionSettingKeys.resolve("unknownKey"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown execution setting: unknownKey");
    }

    @Test
    void parseWithinBounds_validValue_returnsParsed() {
        assertThat(ExecutionSettingKeys.parseWithinBounds("execution-max-concurrent", "5")).isEqualTo(5L);
    }

    @Test
    void parseWithinBounds_boundaryValues_accepted() {
        assertThat(ExecutionSettingKeys.parseWithinBounds("execution-max-concurrent", "1")).isEqualTo(1L);
        assertThat(ExecutionSettingKeys.parseWithinBounds("execution-max-concurrent", "50")).isEqualTo(50L);
    }

    @Test
    void parseWithinBounds_nonNumeric_throws() {
        assertThatThrownBy(() -> ExecutionSettingKeys.parseWithinBounds("execution-max-concurrent", "abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Value must be a number: abc");
    }

    @Test
    void parseWithinBounds_belowMin_throwsWithBounds() {
        assertThatThrownBy(() -> ExecutionSettingKeys.parseWithinBounds("execution-max-concurrent", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("execution-max-concurrent must be between 1 and 50, got 0");
    }

    @Test
    void parseWithinBounds_aboveMax_throwsWithBounds() {
        assertThatThrownBy(() -> ExecutionSettingKeys.parseWithinBounds("execution-max-concurrent", "100"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("execution-max-concurrent must be between 1 and 50, got 100");
    }

    @Test
    void bounds_returnsMinMaxPair() {
        assertThat(ExecutionSettingKeys.bounds("claude-reserved-for-interactive")).containsExactly(0, 10);
    }

    @Test
    void everyAliasMapsToAKnownKey_andEveryKeyHasAnAlias() {
        assertThat(ExecutionSettingKeys.KEY_ALIASES.values())
                .containsExactlyInAnyOrderElementsOf(ExecutionSettingKeys.BOUNDS.keySet());
    }

    @Test
    void everyBoundsEntry_hasMinNotAboveMax() {
        ExecutionSettingKeys.BOUNDS.forEach((key, bounds) ->
                assertThat(bounds[0]).as("bounds of %s", key).isLessThanOrEqualTo(bounds[1]));
    }
}
