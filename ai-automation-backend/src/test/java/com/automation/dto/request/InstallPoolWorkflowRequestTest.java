package com.automation.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InstallPoolWorkflowRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void should_setProjectId_when_constructedWithValue() {
        var request = new InstallPoolWorkflowRequest("proj-123", "My Workflow", true);

        assertThat(request.projectId()).isEqualTo("proj-123");
    }

    @Test
    void should_setName_when_constructedWithValue() {
        var request = new InstallPoolWorkflowRequest("proj-123", "My Workflow", true);

        assertThat(request.name()).isEqualTo("My Workflow");
    }

    @Test
    void should_setForceCreate_when_constructedWithValue() {
        var request = new InstallPoolWorkflowRequest("proj-123", "My Workflow", true);

        assertThat(request.forceCreate()).isTrue();
    }

    @Test
    void should_allowNullName_when_nameIsOptional() {
        var request = new InstallPoolWorkflowRequest("proj-123", null, true);

        assertThat(request.name()).isNull();

        Set<ConstraintViolation<InstallPoolWorkflowRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void should_allowNullForceCreate_when_forceCreateIsOptional() {
        var request = new InstallPoolWorkflowRequest("proj-123", "My Workflow", null);

        assertThat(request.forceCreate()).isNull();

        Set<ConstraintViolation<InstallPoolWorkflowRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void should_passValidation_when_projectIdIsPresent() {
        var request = new InstallPoolWorkflowRequest("proj-123", "My Workflow", false);

        Set<ConstraintViolation<InstallPoolWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_projectIdIsNull() {
        var request = new InstallPoolWorkflowRequest(null, "My Workflow", true);

        Set<ConstraintViolation<InstallPoolWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("projectId");
    }

    @Test
    void should_failValidation_when_projectIdIsBlank() {
        var request = new InstallPoolWorkflowRequest("   ", "My Workflow", true);

        Set<ConstraintViolation<InstallPoolWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("projectId");
    }

    @Test
    void should_failValidation_when_projectIdIsEmpty() {
        var request = new InstallPoolWorkflowRequest("", "My Workflow", true);

        Set<ConstraintViolation<InstallPoolWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("projectId");
    }

    @Test
    void should_beEqual_when_allFieldsMatch() {
        var a = new InstallPoolWorkflowRequest("proj-123", "My Workflow", true);
        var b = new InstallPoolWorkflowRequest("proj-123", "My Workflow", true);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_projectIdDiffers() {
        var a = new InstallPoolWorkflowRequest("proj-123", "My Workflow", true);
        var b = new InstallPoolWorkflowRequest("proj-456", "My Workflow", true);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_nameDiffers() {
        var a = new InstallPoolWorkflowRequest("proj-123", "Workflow A", true);
        var b = new InstallPoolWorkflowRequest("proj-123", "Workflow B", true);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_forceCreateDiffers() {
        var a = new InstallPoolWorkflowRequest("proj-123", "My Workflow", true);
        var b = new InstallPoolWorkflowRequest("proj-123", "My Workflow", false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new InstallPoolWorkflowRequest("proj-123", "My Workflow", true);

        assertThat(request).isNotEqualTo(null);
    }

    @Test
    void should_containFieldsInToString() {
        var request = new InstallPoolWorkflowRequest("proj-123", "My Workflow", true);

        assertThat(request.toString()).contains("proj-123");
        assertThat(request.toString()).contains("My Workflow");
        assertThat(request.toString()).contains("true");
    }
}
