package com.automation.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ChainEntryRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void should_returnWorkflowId_when_constructedWithWorkflowId() {
        var request = new ChainEntryRequest("wf-1", null, 0, null, null);

        assertThat(request.workflowId()).isEqualTo("wf-1");
    }

    @Test
    void should_returnLabel_when_constructedWithLabel() {
        var request = new ChainEntryRequest("wf-1", "My Label", 0, null, null);

        assertThat(request.label()).isEqualTo("My Label");
    }

    @Test
    void should_returnSortOrder_when_constructedWithSortOrder() {
        var request = new ChainEntryRequest("wf-1", null, 5, null, null);

        assertThat(request.sortOrder()).isEqualTo(5);
    }

    @Test
    void should_returnStopOnFailure_when_constructedWithTrue() {
        var request = new ChainEntryRequest("wf-1", null, 0, true, null);

        assertThat(request.stopOnFailure()).isTrue();
    }

    @Test
    void should_returnStopOnFailure_when_constructedWithFalse() {
        var request = new ChainEntryRequest("wf-1", null, 0, false, null);

        assertThat(request.stopOnFailure()).isFalse();
    }

    @Test
    void should_returnInputContext_when_constructedWithPopulatedMap() {
        var context = Map.<String, Object>of("key1", "value1", "key2", 42);
        var request = new ChainEntryRequest("wf-1", null, 0, null, context);

        assertThat(request.inputContext()).hasSize(2);
        assertThat(request.inputContext()).containsEntry("key1", "value1");
        assertThat(request.inputContext()).containsEntry("key2", 42);
    }

    @Test
    void should_returnAllFields_when_fullyPopulated() {
        var context = Map.<String, Object>of("k", "v");
        var request = new ChainEntryRequest("wf-1", "Label", 3, true, context);

        assertThat(request.workflowId()).isEqualTo("wf-1");
        assertThat(request.label()).isEqualTo("Label");
        assertThat(request.sortOrder()).isEqualTo(3);
        assertThat(request.stopOnFailure()).isTrue();
        assertThat(request.inputContext()).containsExactlyEntriesOf(context);
    }

    @Test
    void should_returnNullForNullableFields_when_constructedWithNulls() {
        var request = new ChainEntryRequest("wf-1", null, 0, null, null);

        assertThat(request.label()).isNull();
        assertThat(request.stopOnFailure()).isNull();
        assertThat(request.inputContext()).isNull();
    }

    @Test
    void should_returnEmptyMap_when_inputContextIsEmpty() {
        var request = new ChainEntryRequest("wf-1", null, 0, null, Map.of());

        assertThat(request.inputContext()).isEmpty();
    }

    // --- Validation tests for @NotBlank workflowId ---

    @Test
    void should_passValidation_when_workflowIdIsNotBlank() {
        var request = new ChainEntryRequest("wf-1", "Label", 1, false, Map.of());

        Set<ConstraintViolation<ChainEntryRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_workflowIdIsNull() {
        var request = new ChainEntryRequest(null, "Label", 1, false, Map.of());

        Set<ConstraintViolation<ChainEntryRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("workflowId");
    }

    @Test
    void should_failValidation_when_workflowIdIsBlank() {
        var request = new ChainEntryRequest("   ", "Label", 1, false, Map.of());

        Set<ConstraintViolation<ChainEntryRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("workflowId");
    }

    @Test
    void should_failValidation_when_workflowIdIsEmpty() {
        var request = new ChainEntryRequest("", "Label", 1, false, Map.of());

        Set<ConstraintViolation<ChainEntryRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("workflowId");
    }

    // --- equals / hashCode ---

    @Test
    void should_beEqual_when_allFieldsMatch() {
        var context = Map.<String, Object>of("k", "v");
        var a = new ChainEntryRequest("wf-1", "Label", 3, true, context);
        var b = new ChainEntryRequest("wf-1", "Label", 3, true, context);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveNullOptionalFields() {
        var a = new ChainEntryRequest("wf-1", null, 0, null, null);
        var b = new ChainEntryRequest("wf-1", null, 0, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_workflowIdDiffers() {
        var a = new ChainEntryRequest("wf-1", "Label", 1, true, null);
        var b = new ChainEntryRequest("wf-2", "Label", 1, true, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_labelDiffers() {
        var a = new ChainEntryRequest("wf-1", "Label A", 1, null, null);
        var b = new ChainEntryRequest("wf-1", "Label B", 1, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_sortOrderDiffers() {
        var a = new ChainEntryRequest("wf-1", "Label", 1, null, null);
        var b = new ChainEntryRequest("wf-1", "Label", 2, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_stopOnFailureDiffers() {
        var a = new ChainEntryRequest("wf-1", null, 0, true, null);
        var b = new ChainEntryRequest("wf-1", null, 0, false, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_inputContextDiffers() {
        var a = new ChainEntryRequest("wf-1", null, 0, null, Map.of("k", "v1"));
        var b = new ChainEntryRequest("wf-1", null, 0, null, Map.of("k", "v2"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new ChainEntryRequest("wf-1", "Label", 1, true, Map.of());

        assertThat(request).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldNamesInToString() {
        var request = new ChainEntryRequest("wf-1", "Label", 3, true, Map.of("k", "v"));

        assertThat(request.toString()).contains("workflowId");
        assertThat(request.toString()).contains("label");
        assertThat(request.toString()).contains("sortOrder");
        assertThat(request.toString()).contains("stopOnFailure");
        assertThat(request.toString()).contains("inputContext");
    }

    @Test
    void should_containValuesInToString() {
        var request = new ChainEntryRequest("wf-1", "Label", 3, true, Map.of());

        assertThat(request.toString()).contains("wf-1");
        assertThat(request.toString()).contains("Label");
        assertThat(request.toString()).contains("3");
    }
}
