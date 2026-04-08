package com.automation.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateCompletionTriggerRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    // --- Full construction ---

    @Test
    void should_setAllFields_when_constructedWithAllArgs() {
        var context = Map.<String, Object>of("key", "value", "num", 42);

        var request = new CreateCompletionTriggerRequest(
                "source-wf-1", "target-wf-2",
                true, false, true,
                "Trigger on completion", context);

        assertThat(request.sourceWorkflowId()).isEqualTo("source-wf-1");
        assertThat(request.targetWorkflowId()).isEqualTo("target-wf-2");
        assertThat(request.triggerOnSuccess()).isTrue();
        assertThat(request.triggerOnFailure()).isFalse();
        assertThat(request.triggerAlways()).isTrue();
        assertThat(request.description()).isEqualTo("Trigger on completion");
        assertThat(request.contextVariables()).isEqualTo(context);
    }

    // --- Boolean defaults ---

    @Test
    void should_defaultBooleansToFalse_when_constructedWithFalse() {
        var request = new CreateCompletionTriggerRequest(
                "src", "tgt", false, false, false, null, null);

        assertThat(request.triggerOnSuccess()).isFalse();
        assertThat(request.triggerOnFailure()).isFalse();
        assertThat(request.triggerAlways()).isFalse();
    }

    @Test
    void should_setAllBooleansTrue_when_constructedWithTrue() {
        var request = new CreateCompletionTriggerRequest(
                "src", "tgt", true, true, true, null, null);

        assertThat(request.triggerOnSuccess()).isTrue();
        assertThat(request.triggerOnFailure()).isTrue();
        assertThat(request.triggerAlways()).isTrue();
    }

    // --- Null cases for optional fields ---

    @Test
    void should_acceptNullDescription() {
        var request = new CreateCompletionTriggerRequest(
                "src", "tgt", false, false, false, null, Map.of());

        assertThat(request.description()).isNull();
    }

    @Test
    void should_acceptNullContextVariables() {
        var request = new CreateCompletionTriggerRequest(
                "src", "tgt", false, false, false, "desc", null);

        assertThat(request.contextVariables()).isNull();
    }

    @Test
    void should_acceptBothOptionalFieldsNull() {
        var request = new CreateCompletionTriggerRequest(
                "src", "tgt", false, false, false, null, null);

        assertThat(request.description()).isNull();
        assertThat(request.contextVariables()).isNull();
    }

    // --- @NotBlank validation: sourceWorkflowId ---

    @Test
    void should_passValidation_when_requiredFieldsPresent() {
        var request = new CreateCompletionTriggerRequest(
                "source-1", "target-1", true, false, false, null, null);

        Set<ConstraintViolation<CreateCompletionTriggerRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_sourceWorkflowIdIsNull() {
        var request = new CreateCompletionTriggerRequest(
                null, "target-1", false, false, false, null, null);

        Set<ConstraintViolation<CreateCompletionTriggerRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("sourceWorkflowId");
    }

    @Test
    void should_failValidation_when_sourceWorkflowIdIsEmpty() {
        var request = new CreateCompletionTriggerRequest(
                "", "target-1", false, false, false, null, null);

        Set<ConstraintViolation<CreateCompletionTriggerRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("sourceWorkflowId");
    }

    @Test
    void should_failValidation_when_sourceWorkflowIdIsBlank() {
        var request = new CreateCompletionTriggerRequest(
                "   ", "target-1", false, false, false, null, null);

        Set<ConstraintViolation<CreateCompletionTriggerRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("sourceWorkflowId");
    }

    // --- @NotBlank validation: targetWorkflowId ---

    @Test
    void should_failValidation_when_targetWorkflowIdIsNull() {
        var request = new CreateCompletionTriggerRequest(
                "source-1", null, false, false, false, null, null);

        Set<ConstraintViolation<CreateCompletionTriggerRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("targetWorkflowId");
    }

    @Test
    void should_failValidation_when_targetWorkflowIdIsEmpty() {
        var request = new CreateCompletionTriggerRequest(
                "source-1", "", false, false, false, null, null);

        Set<ConstraintViolation<CreateCompletionTriggerRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("targetWorkflowId");
    }

    @Test
    void should_failValidation_when_targetWorkflowIdIsBlank() {
        var request = new CreateCompletionTriggerRequest(
                "source-1", "  ", false, false, false, null, null);

        Set<ConstraintViolation<CreateCompletionTriggerRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("targetWorkflowId");
    }

    // --- Both required fields missing ---

    @Test
    void should_failValidation_when_bothRequiredFieldsMissing() {
        var request = new CreateCompletionTriggerRequest(
                null, null, false, false, false, null, null);

        Set<ConstraintViolation<CreateCompletionTriggerRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(2);
        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("sourceWorkflowId", "targetWorkflowId");
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var ctx = Map.<String, Object>of("k", "v");
        var a = new CreateCompletionTriggerRequest(
                "src", "tgt", true, false, true, "desc", ctx);
        var b = new CreateCompletionTriggerRequest(
                "src", "tgt", true, false, true, "desc", ctx);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentSourceWorkflowId() {
        var a = new CreateCompletionTriggerRequest(
                "src-a", "tgt", false, false, false, null, null);
        var b = new CreateCompletionTriggerRequest(
                "src-b", "tgt", false, false, false, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentTargetWorkflowId() {
        var a = new CreateCompletionTriggerRequest(
                "src", "tgt-a", false, false, false, null, null);
        var b = new CreateCompletionTriggerRequest(
                "src", "tgt-b", false, false, false, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentBooleanFlags() {
        var a = new CreateCompletionTriggerRequest(
                "src", "tgt", true, false, false, null, null);
        var b = new CreateCompletionTriggerRequest(
                "src", "tgt", false, true, false, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    // --- toString ---

    @Test
    void should_containFieldNames_inToString() {
        var request = new CreateCompletionTriggerRequest(
                "src-1", "tgt-1", true, false, false, "my desc", null);

        String str = request.toString();

        assertThat(str).contains("src-1");
        assertThat(str).contains("tgt-1");
        assertThat(str).contains("my desc");
    }
}
