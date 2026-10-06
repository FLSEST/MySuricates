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
    void boundsEntry_exposesMinMaxAndNumberKind() {
        ExecutionSettingKeys.Bound bound = ExecutionSettingKeys.BOUNDS.get("claude-reserved-for-interactive");

        assertThat(bound.min()).isEqualTo(0);
        assertThat(bound.max()).isEqualTo(10);
        assertThat(bound.decimal()).isFalse();
    }

    /**
     * TODO 4218fcaa: integer and fraction keys live in <b>one</b> map, so a new
     * key has to be registered in only one place.
     */
    @Test
    void integerAndDecimalKeys_liveInTheOneBoundsMap() {
        assertThat(ExecutionSettingKeys.BOUNDS.get("todo-similarity-keyword-threshold").decimal()).isTrue();
        assertThat(ExecutionSettingKeys.BOUNDS.get("execution-max-concurrent").decimal()).isFalse();
    }

    @Test
    void everyAliasMapsToAKnownKey_andEveryKeyHasAnAlias() {
        Set<String> allKeys = new HashSet<>(ExecutionSettingKeys.BOUNDS.keySet());
        assertThat(ExecutionSettingKeys.KEY_ALIASES.values()).containsExactlyInAnyOrderElementsOf(allKeys);
    }

    @Test
    void everyBoundsEntry_hasMinNotAboveMax() {
        ExecutionSettingKeys.BOUNDS.forEach((key, bound) ->
                assertThat(bound.min()).as("bounds of %s", key).isLessThanOrEqualTo(bound.max()));
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
