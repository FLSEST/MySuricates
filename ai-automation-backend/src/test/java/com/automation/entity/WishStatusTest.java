package com.automation.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WishStatusTest {

    @Test
    void should_have_eleven_values() {
        assertThat(WishStatus.values()).hasSize(11);
    }

    @Test
    void should_contain_all_expected_constants() {
        assertThat(WishStatus.values())
                .containsExactly(
                        WishStatus.RECEIVED,
                        WishStatus.DECOMPOSING,
                        WishStatus.AWAITING_DECOMPOSE_APPROVAL,
                        WishStatus.EXECUTING,
                        WishStatus.EVALUATING,
                        WishStatus.REFINING,
                        WishStatus.AWAITING_CLARIFICATION,
                        WishStatus.AWAITING_APPROVAL,
                        WishStatus.COMPLETED,
                        WishStatus.FAILED,
                        WishStatus.CANCELLED
                );
    }

    @Test
    void should_resolve_received_via_valueOf() {
        assertThat(WishStatus.valueOf("RECEIVED")).isEqualTo(WishStatus.RECEIVED);
    }

    @Test
    void should_resolve_decomposing_via_valueOf() {
        assertThat(WishStatus.valueOf("DECOMPOSING")).isEqualTo(WishStatus.DECOMPOSING);
    }

    @Test
    void should_resolve_awaiting_decompose_approval_via_valueOf() {
        assertThat(WishStatus.valueOf("AWAITING_DECOMPOSE_APPROVAL"))
                .isEqualTo(WishStatus.AWAITING_DECOMPOSE_APPROVAL);
    }

    @Test
    void should_resolve_executing_via_valueOf() {
        assertThat(WishStatus.valueOf("EXECUTING")).isEqualTo(WishStatus.EXECUTING);
    }

    @Test
    void should_resolve_evaluating_via_valueOf() {
        assertThat(WishStatus.valueOf("EVALUATING")).isEqualTo(WishStatus.EVALUATING);
    }

    @Test
    void should_resolve_refining_via_valueOf() {
        assertThat(WishStatus.valueOf("REFINING")).isEqualTo(WishStatus.REFINING);
    }

    @Test
    void should_resolve_awaiting_clarification_via_valueOf() {
        assertThat(WishStatus.valueOf("AWAITING_CLARIFICATION"))
                .isEqualTo(WishStatus.AWAITING_CLARIFICATION);
    }

    @Test
    void should_resolve_awaiting_approval_via_valueOf() {
        assertThat(WishStatus.valueOf("AWAITING_APPROVAL"))
                .isEqualTo(WishStatus.AWAITING_APPROVAL);
    }

    @Test
    void should_resolve_completed_via_valueOf() {
        assertThat(WishStatus.valueOf("COMPLETED")).isEqualTo(WishStatus.COMPLETED);
    }

    @Test
    void should_resolve_failed_via_valueOf() {
        assertThat(WishStatus.valueOf("FAILED")).isEqualTo(WishStatus.FAILED);
    }

    @Test
    void should_resolve_cancelled_via_valueOf() {
        assertThat(WishStatus.valueOf("CANCELLED")).isEqualTo(WishStatus.CANCELLED);
    }

    @Test
    void should_throw_when_valueOf_called_with_unknown_name() {
        assertThatThrownBy(() -> WishStatus.valueOf("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_throw_when_valueOf_called_with_null() {
        assertThatThrownBy(() -> WishStatus.valueOf(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_have_correct_name_for_each_constant() {
        assertThat(WishStatus.RECEIVED.name()).isEqualTo("RECEIVED");
        assertThat(WishStatus.DECOMPOSING.name()).isEqualTo("DECOMPOSING");
        assertThat(WishStatus.AWAITING_DECOMPOSE_APPROVAL.name()).isEqualTo("AWAITING_DECOMPOSE_APPROVAL");
        assertThat(WishStatus.EXECUTING.name()).isEqualTo("EXECUTING");
        assertThat(WishStatus.EVALUATING.name()).isEqualTo("EVALUATING");
        assertThat(WishStatus.REFINING.name()).isEqualTo("REFINING");
        assertThat(WishStatus.AWAITING_CLARIFICATION.name()).isEqualTo("AWAITING_CLARIFICATION");
        assertThat(WishStatus.AWAITING_APPROVAL.name()).isEqualTo("AWAITING_APPROVAL");
        assertThat(WishStatus.COMPLETED.name()).isEqualTo("COMPLETED");
        assertThat(WishStatus.FAILED.name()).isEqualTo("FAILED");
        assertThat(WishStatus.CANCELLED.name()).isEqualTo("CANCELLED");
    }

    @Test
    void should_have_unique_ordinals() {
        assertThat(WishStatus.values())
                .extracting(Enum::ordinal)
                .doesNotHaveDuplicates();
    }
}
