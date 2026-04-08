package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SyncResultTest {

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructedWithPopulatedValues() {
        var errors = List.of("Error A", "Error B");

        var result = new SyncResult(3, 5, 2, 1, errors);

        assertThat(result.projectsSynced()).isEqualTo(3);
        assertThat(result.workflowsSynced()).isEqualTo(5);
        assertThat(result.skillsSynced()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.errors()).containsExactly("Error A", "Error B");
    }

    @Test
    void should_returnZeroCounters_when_constructedWithZeros() {
        var result = new SyncResult(0, 0, 0, 0, List.of());

        assertThat(result.projectsSynced()).isZero();
        assertThat(result.workflowsSynced()).isZero();
        assertThat(result.skillsSynced()).isZero();
        assertThat(result.skipped()).isZero();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void should_returnEmptyErrors_when_constructedWithEmptyList() {
        var result = new SyncResult(1, 2, 3, 4, List.of());

        assertThat(result.errors()).isEmpty();
    }

    @Test
    void should_returnPopulatedErrors_when_constructedWithErrors() {
        var errors = List.of("Failed to sync project X", "Skill Y not found");

        var result = new SyncResult(0, 0, 0, 2, errors);

        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors()).containsExactly("Failed to sync project X", "Skill Y not found");
    }

    @Test
    void should_acceptNullErrors_when_constructed() {
        var result = new SyncResult(1, 2, 3, 0, null);

        assertThat(result.errors()).isNull();
    }

    // --- null vs empty list ---

    @Test
    void should_distinguishNullFromEmptyList_when_errorsCompared() {
        var withNull = new SyncResult(1, 2, 3, 0, null);
        var withEmpty = new SyncResult(1, 2, 3, 0, List.of());

        assertThat(withNull.errors()).isNull();
        assertThat(withEmpty.errors()).isEmpty();
        assertThat(withNull).isNotEqualTo(withEmpty);
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new SyncResult(3, 5, 2, 1, List.of("Error A"));
        var b = new SyncResult(3, 5, 2, 1, List.of("Error A"));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveEmptyErrors() {
        var a = new SyncResult(0, 0, 0, 0, List.of());
        var b = new SyncResult(0, 0, 0, 0, List.of());

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveNullErrors() {
        var a = new SyncResult(1, 2, 3, 4, null);
        var b = new SyncResult(1, 2, 3, 4, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentProjectsSynced() {
        var a = new SyncResult(3, 5, 2, 1, List.of());
        var b = new SyncResult(4, 5, 2, 1, List.of());

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentWorkflowsSynced() {
        var a = new SyncResult(3, 5, 2, 1, List.of());
        var b = new SyncResult(3, 6, 2, 1, List.of());

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentSkillsSynced() {
        var a = new SyncResult(3, 5, 2, 1, List.of());
        var b = new SyncResult(3, 5, 9, 1, List.of());

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentSkipped() {
        var a = new SyncResult(3, 5, 2, 1, List.of());
        var b = new SyncResult(3, 5, 2, 7, List.of());

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentErrors() {
        var a = new SyncResult(3, 5, 2, 1, List.of("Error A"));
        var b = new SyncResult(3, 5, 2, 1, List.of("Error B"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var result = new SyncResult(0, 0, 0, 0, List.of());

        assertThat(result).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var result = new SyncResult(3, 5, 2, 1, List.of("Error A"));
        var str = result.toString();

        assertThat(str).contains("projectsSynced");
        assertThat(str).contains("3");
        assertThat(str).contains("workflowsSynced");
        assertThat(str).contains("5");
        assertThat(str).contains("skillsSynced");
        assertThat(str).contains("2");
        assertThat(str).contains("skipped");
        assertThat(str).contains("1");
        assertThat(str).contains("Error A");
    }

    @Test
    void should_notThrow_when_toStringCalledWithNullErrors() {
        var result = new SyncResult(0, 0, 0, 0, null);
        var str = result.toString();

        assertThat(str).isNotNull();
    }
}
