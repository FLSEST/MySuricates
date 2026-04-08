package com.automation.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CreateTodoTriggerRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    // --- Accessor tests ---

    @Test
    void should_returnAllFields_when_fullyConstructed() {
        Map<String, Object> context = Map.of("key", "value");
        var request = new CreateTodoTriggerRequest(
                "bug", "project-1", "AI", "HIGH",
                "wf-123", context, "A description", 3, true, false
        );

        assertThat(request.tagPattern()).isEqualTo("bug");
        assertThat(request.projectId()).isEqualTo("project-1");
        assertThat(request.sourceFilter()).isEqualTo("AI");
        assertThat(request.minPriority()).isEqualTo("HIGH");
        assertThat(request.targetWorkflowId()).isEqualTo("wf-123");
        assertThat(request.contextTemplate()).containsEntry("key", "value");
        assertThat(request.description()).isEqualTo("A description");
        assertThat(request.maxAttempts()).isEqualTo(3);
        assertThat(request.autoClose()).isTrue();
        assertThat(request.processBacklog()).isFalse();
    }

    @Test
    void should_returnNullOptionalFields_when_constructedWithOnlyRequired() {
        var request = new CreateTodoTriggerRequest(
                "bug", null, null, null,
                "wf-123", null, null, null, null, null
        );

        assertThat(request.tagPattern()).isEqualTo("bug");
        assertThat(request.targetWorkflowId()).isEqualTo("wf-123");
        assertThat(request.projectId()).isNull();
        assertThat(request.sourceFilter()).isNull();
        assertThat(request.minPriority()).isNull();
        assertThat(request.contextTemplate()).isNull();
        assertThat(request.description()).isNull();
        assertThat(request.maxAttempts()).isNull();
        assertThat(request.autoClose()).isNull();
        assertThat(request.processBacklog()).isNull();
    }

    // --- Validation: tagPattern ---

    @Test
    void should_passValidation_when_tagPatternAndTargetWorkflowIdArePresent() {
        var request = new CreateTodoTriggerRequest(
                "bug", null, null, null,
                "wf-123", null, null, null, null, null
        );

        Set<ConstraintViolation<CreateTodoTriggerRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_tagPatternIsNull() {
        var request = new CreateTodoTriggerRequest(
                null, null, null, null,
                "wf-123", null, null, null, null, null
        );

        Set<ConstraintViolation<CreateTodoTriggerRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violatedProperties(violations)).contains("tagPattern");
    }

    @Test
    void should_failValidation_when_tagPatternIsEmpty() {
        var request = new CreateTodoTriggerRequest(
                "", null, null, null,
                "wf-123", null, null, null, null, null
        );

        Set<ConstraintViolation<CreateTodoTriggerRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violatedProperties(violations)).contains("tagPattern");
    }

    @Test
    void should_failValidation_when_tagPatternIsBlank() {
        var request = new CreateTodoTriggerRequest(
                "   ", null, null, null,
                "wf-123", null, null, null, null, null
        );

        Set<ConstraintViolation<CreateTodoTriggerRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violatedProperties(violations)).contains("tagPattern");
    }

    // --- Validation: targetWorkflowId ---

    @Test
    void should_failValidation_when_targetWorkflowIdIsNull() {
        var request = new CreateTodoTriggerRequest(
                "bug", null, null, null,
                null, null, null, null, null, null
        );

        Set<ConstraintViolation<CreateTodoTriggerRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violatedProperties(violations)).contains("targetWorkflowId");
    }

    @Test
    void should_failValidation_when_targetWorkflowIdIsEmpty() {
        var request = new CreateTodoTriggerRequest(
                "bug", null, null, null,
                "", null, null, null, null, null
        );

        Set<ConstraintViolation<CreateTodoTriggerRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violatedProperties(violations)).contains("targetWorkflowId");
    }

    @Test
    void should_failValidation_when_targetWorkflowIdIsBlank() {
        var request = new CreateTodoTriggerRequest(
                "bug", null, null, null,
                "   ", null, null, null, null, null
        );

        Set<ConstraintViolation<CreateTodoTriggerRequest>> violations = validator.validate(request);

        assertThat(violations).isNotEmpty();
        assertThat(violatedProperties(violations)).contains("targetWorkflowId");
    }

    // --- Validation: both fields invalid ---

    @Test
    void should_failValidation_when_bothRequiredFieldsAreNull() {
        var request = new CreateTodoTriggerRequest(
                null, null, null, null,
                null, null, null, null, null, null
        );

        Set<ConstraintViolation<CreateTodoTriggerRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(2);
        assertThat(violatedProperties(violations)).containsExactlyInAnyOrder("tagPattern", "targetWorkflowId");
    }

    // --- Optional fields ---

    @Test
    void should_acceptContextTemplate_when_provided() {
        Map<String, Object> context = Map.of("todoTitle", "{{title}}", "todoPriority", "{{priority}}");
        var request = new CreateTodoTriggerRequest(
                "bug", null, null, null,
                "wf-123", context, null, null, null, null
        );

        assertThat(request.contextTemplate()).hasSize(2);
        assertThat(request.contextTemplate()).containsEntry("todoTitle", "{{title}}");
    }

    @Test
    void should_acceptMaxAttempts_when_provided() {
        var request = new CreateTodoTriggerRequest(
                "bug", null, null, null,
                "wf-123", null, null, 5, null, null
        );

        assertThat(request.maxAttempts()).isEqualTo(5);
    }

    @Test
    void should_acceptAutoCloseTrue_when_provided() {
        var request = new CreateTodoTriggerRequest(
                "bug", null, null, null,
                "wf-123", null, null, null, true, null
        );

        assertThat(request.autoClose()).isTrue();
    }

    @Test
    void should_acceptAutoCloseFalse_when_provided() {
        var request = new CreateTodoTriggerRequest(
                "bug", null, null, null,
                "wf-123", null, null, null, false, null
        );

        assertThat(request.autoClose()).isFalse();
    }

    @Test
    void should_acceptProcessBacklog_when_provided() {
        var request = new CreateTodoTriggerRequest(
                "bug", null, null, null,
                "wf-123", null, null, null, null, true
        );

        assertThat(request.processBacklog()).isTrue();
    }

    // --- equals / hashCode ---

    @Test
    void should_beEqual_when_allFieldsMatch() {
        Map<String, Object> context = Map.of("key", "value");
        var a = new CreateTodoTriggerRequest(
                "bug", "project-1", "AI", "HIGH",
                "wf-123", context, "desc", 3, true, false
        );
        var b = new CreateTodoTriggerRequest(
                "bug", "project-1", "AI", "HIGH",
                "wf-123", context, "desc", 3, true, false
        );

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_tagPatternDiffers() {
        var a = new CreateTodoTriggerRequest(
                "bug", null, null, null,
                "wf-123", null, null, null, null, null
        );
        var b = new CreateTodoTriggerRequest(
                "feature", null, null, null,
                "wf-123", null, null, null, null, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_targetWorkflowIdDiffers() {
        var a = new CreateTodoTriggerRequest(
                "bug", null, null, null,
                "wf-123", null, null, null, null, null
        );
        var b = new CreateTodoTriggerRequest(
                "bug", null, null, null,
                "wf-456", null, null, null, null, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new CreateTodoTriggerRequest(
                "bug", null, null, null,
                "wf-123", null, null, null, null, null
        );

        assertThat(request).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldNamesInToString() {
        var request = new CreateTodoTriggerRequest(
                "bug", "project-1", "AI", "HIGH",
                "wf-123", null, "desc", 3, true, false
        );

        String str = request.toString();
        assertThat(str).contains("tagPattern");
        assertThat(str).contains("bug");
        assertThat(str).contains("targetWorkflowId");
        assertThat(str).contains("wf-123");
        assertThat(str).contains("maxAttempts");
        assertThat(str).contains("autoClose");
        assertThat(str).contains("processBacklog");
    }

    // --- Helper ---

    private Set<String> violatedProperties(Set<ConstraintViolation<CreateTodoTriggerRequest>> violations) {
        return violations.stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
