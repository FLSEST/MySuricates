package com.automation.service.system;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
        Set<String> allKeys = new HashSet<>(ExecutionSettingKeys.BOUNDS.keySet());
        allKeys.addAll(ExecutionSettingKeys.DECIMAL_BOUNDS.keySet());
        assertThat(ExecutionSettingKeys.KEY_ALIASES.values()).containsExactlyInAnyOrderElementsOf(allKeys);
    }

    @Test
    void everyBoundsEntry_hasMinNotAboveMax() {
        ExecutionSettingKeys.BOUNDS.forEach((key, bounds) ->
                assertThat(bounds[0]).as("bounds of %s", key).isLessThanOrEqualTo(bounds[1]));
        ExecutionSettingKeys.DECIMAL_BOUNDS.forEach((key, bounds) ->
                assertThat(bounds[0]).as("bounds of %s", key).isLessThanOrEqualTo(bounds[1]));
    }

    @Test
    void aDecimalKeyIsResolvedLikeAnyOther() {
        assertThat(ExecutionSettingKeys.resolve("todoSimilarityKeywordThreshold"))
                .isEqualTo("todo-similarity-keyword-threshold");
    }

    @Test
    void validate_decimalKey_acceptsFractionsWithinBounds() {
        assertThatCode(() -> ExecutionSettingKeys.validate("todo-similarity-keyword-threshold", "0.35"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ExecutionSettingKeys.validate("todo-similarity-keyword-threshold", "0.05"))
                .doesNotThrowAnyException();
        assertThatCode(() -> ExecutionSettingKeys.validate("todo-similarity-keyword-threshold", "0.9"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_decimalKey_rejectsOutOfBoundsAndNonNumbers() {
        assertThatThrownBy(() -> ExecutionSettingKeys.validate("todo-similarity-keyword-threshold", "0.01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("todo-similarity-keyword-threshold must be between 0.05 and 0.9, got 0.01");
        assertThatThrownBy(() -> ExecutionSettingKeys.validate("todo-similarity-keyword-threshold", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be between 0.05 and 0.9");
        assertThatThrownBy(() -> ExecutionSettingKeys.validate("todo-similarity-keyword-threshold", "viel"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Value must be a number: viel");
    }

    @Test
    void validate_integerKey_stillUsesTheIntegerBounds() {
        assertThatThrownBy(() -> ExecutionSettingKeys.validate("execution-max-concurrent", "0.5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Value must be a number: 0.5");
    }
}
