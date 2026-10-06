package com.automation.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConvertRunToWorkflowRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void should_setProjectId_when_constructedWithValue() {
        var request = new ConvertRunToWorkflowRequest("proj-123", "My Workflow");

        assertThat(request.projectId()).isEqualTo("proj-123");
    }

    @Test
    void should_setName_when_constructedWithValue() {
        var request = new ConvertRunToWorkflowRequest("proj-123", "My Workflow");

        assertThat(request.name()).isEqualTo("My Workflow");
    }

    @Test
    void should_allowNullName_when_nameIsOptional() {
        var request = new ConvertRunToWorkflowRequest("proj-123", null);

        assertThat(request.name()).isNull();

        Set<ConstraintViolation<ConvertRunToWorkflowRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void should_passValidation_when_projectIdIsPresent() {
        var request = new ConvertRunToWorkflowRequest("proj-123", "My Workflow");

        Set<ConstraintViolation<ConvertRunToWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_projectIdIsNull() {
        var request = new ConvertRunToWorkflowRequest(null, "My Workflow");

        Set<ConstraintViolation<ConvertRunToWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("projectId");
    }

    @Test
    void should_failValidation_when_projectIdIsBlank() {
        var request = new ConvertRunToWorkflowRequest("   ", "My Workflow");

        Set<ConstraintViolation<ConvertRunToWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("projectId");
    }

    @Test
    void should_failValidation_when_projectIdIsEmpty() {
        var request = new ConvertRunToWorkflowRequest("", "My Workflow");

        Set<ConstraintViolation<ConvertRunToWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("projectId");
    }

    @Test
    void should_beEqual_when_bothFieldsMatch() {
        var a = new ConvertRunToWorkflowRequest("proj-123", "My Workflow");
        var b = new ConvertRunToWorkflowRequest("proj-123", "My Workflow");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_projectIdDiffers() {
        var a = new ConvertRunToWorkflowRequest("proj-123", "My Workflow");
        var b = new ConvertRunToWorkflowRequest("proj-456", "My Workflow");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_nameDiffers() {
        var a = new ConvertRunToWorkflowRequest("proj-123", "Workflow A");
        var b = new ConvertRunToWorkflowRequest("proj-123", "Workflow B");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new ConvertRunToWorkflowRequest("proj-123", "My Workflow");

        assertThat(request).isNotEqualTo(null);
    }

    @Test
    void should_containFieldsInToString() {
        var request = new ConvertRunToWorkflowRequest("proj-123", "My Workflow");

        assertThat(request.toString()).contains("proj-123");
        assertThat(request.toString()).contains("My Workflow");
    }

    /** Old clients: only project and name — without a checked proposal (TODO 935af6d3). */
    @Test
    void should_carryNoReviewedSuggestion_when_builtFromProjectAndNameOnly() {
        var request = new ConvertRunToWorkflowRequest("proj-123", "My Workflow");

        assertThat(request.description()).isNull();
        assertThat(request.blocks()).isNull();
    }
}
