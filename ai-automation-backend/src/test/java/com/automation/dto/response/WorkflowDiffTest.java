package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDiffTest {

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        var fields = List.of("model", "name");
        var diff = new WorkflowDiff(fields, "sonnet", "opus", 2, 1);

        assertThat(diff.changedFields()).containsExactly("model", "name");
        assertThat(diff.modelBefore()).isEqualTo("sonnet");
        assertThat(diff.modelAfter()).isEqualTo("opus");
        assertThat(diff.blocksAdded()).isEqualTo(2);
        assertThat(diff.blocksRemoved()).isEqualTo(1);
    }

    @Test
    void should_returnEmptyList_when_constructedWithEmptyChangedFields() {
        var diff = new WorkflowDiff(List.of(), "before", "after", 0, 0);

        assertThat(diff.changedFields()).isEmpty();
        assertThat(diff.modelBefore()).isEqualTo("before");
        assertThat(diff.modelAfter()).isEqualTo("after");
    }

    @Test
    void should_acceptNullChangedFields_when_constructed() {
        var diff = new WorkflowDiff(null, "sonnet", "opus", 1, 0);

        assertThat(diff.changedFields()).isNull();
        assertThat(diff.modelBefore()).isEqualTo("sonnet");
        assertThat(diff.modelAfter()).isEqualTo("opus");
    }

    @Test
    void should_acceptNullModelBefore_when_constructed() {
        var diff = new WorkflowDiff(List.of("model"), null, "opus", 0, 0);

        assertThat(diff.modelBefore()).isNull();
        assertThat(diff.modelAfter()).isEqualTo("opus");
    }

    @Test
    void should_acceptNullModelAfter_when_constructed() {
        var diff = new WorkflowDiff(List.of("model"), "sonnet", null, 0, 0);

        assertThat(diff.modelBefore()).isEqualTo("sonnet");
        assertThat(diff.modelAfter()).isNull();
    }

    @Test
    void should_acceptAllNulls_when_constructed() {
        var diff = new WorkflowDiff(null, null, null, 0, 0);

        assertThat(diff.changedFields()).isNull();
        assertThat(diff.modelBefore()).isNull();
        assertThat(diff.modelAfter()).isNull();
        assertThat(diff.blocksAdded()).isZero();
        assertThat(diff.blocksRemoved()).isZero();
    }

    @Test
    void should_returnZeroCounts_when_noBlockChanges() {
        var diff = new WorkflowDiff(List.of("name"), "s", "o", 0, 0);

        assertThat(diff.blocksAdded()).isZero();
        assertThat(diff.blocksRemoved()).isZero();
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new WorkflowDiff(List.of("model"), "sonnet", "opus", 2, 1);
        var b = new WorkflowDiff(List.of("model"), "sonnet", "opus", 2, 1);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveEmptyChangedFields() {
        var a = new WorkflowDiff(List.of(), null, null, 0, 0);
        var b = new WorkflowDiff(List.of(), null, null, 0, 0);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveNullChangedFields() {
        var a = new WorkflowDiff(null, null, null, 0, 0);
        var b = new WorkflowDiff(null, null, null, 0, 0);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentChangedFields() {
        var a = new WorkflowDiff(List.of("model"), "s", "o", 0, 0);
        var b = new WorkflowDiff(List.of("name"), "s", "o", 0, 0);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_changedFieldsNullVsEmpty() {
        var a = new WorkflowDiff(null, "s", "o", 0, 0);
        var b = new WorkflowDiff(List.of(), "s", "o", 0, 0);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentModelBefore() {
        var a = new WorkflowDiff(List.of(), "sonnet", "opus", 0, 0);
        var b = new WorkflowDiff(List.of(), "haiku", "opus", 0, 0);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentModelAfter() {
        var a = new WorkflowDiff(List.of(), "sonnet", "opus", 0, 0);
        var b = new WorkflowDiff(List.of(), "sonnet", "haiku", 0, 0);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentBlocksAdded() {
        var a = new WorkflowDiff(List.of(), "s", "o", 1, 0);
        var b = new WorkflowDiff(List.of(), "s", "o", 2, 0);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentBlocksRemoved() {
        var a = new WorkflowDiff(List.of(), "s", "o", 0, 1);
        var b = new WorkflowDiff(List.of(), "s", "o", 0, 2);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var diff = new WorkflowDiff(List.of(), "s", "o", 0, 0);

        assertThat(diff).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldNames_when_toStringCalled() {
        var diff = new WorkflowDiff(List.of(), "sonnet", "opus", 0, 0);
        var str = diff.toString();

        assertThat(str).contains("changedFields");
        assertThat(str).contains("modelBefore");
        assertThat(str).contains("modelAfter");
        assertThat(str).contains("blocksAdded");
        assertThat(str).contains("blocksRemoved");
    }

    @Test
    void should_containFieldValues_when_toStringCalledWithPopulatedData() {
        var diff = new WorkflowDiff(List.of("model", "name"), "sonnet", "opus", 3, 2);
        var str = diff.toString();

        assertThat(str).contains("model");
        assertThat(str).contains("name");
        assertThat(str).contains("sonnet");
        assertThat(str).contains("opus");
        assertThat(str).contains("3");
        assertThat(str).contains("2");
    }
}
