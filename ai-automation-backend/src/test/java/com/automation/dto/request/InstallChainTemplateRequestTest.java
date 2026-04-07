package com.automation.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InstallChainTemplateRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void should_setProjectId_when_constructedWithValue() {
        var request = new InstallChainTemplateRequest("proj-123", "My Chain");

        assertThat(request.projectId()).isEqualTo("proj-123");
    }

    @Test
    void should_setChainName_when_constructedWithValue() {
        var request = new InstallChainTemplateRequest("proj-123", "My Chain");

        assertThat(request.chainName()).isEqualTo("My Chain");
    }

    @Test
    void should_allowNullChainName_when_chainNameIsOptional() {
        var request = new InstallChainTemplateRequest("proj-123", null);

        assertThat(request.chainName()).isNull();

        Set<ConstraintViolation<InstallChainTemplateRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void should_passValidation_when_projectIdIsPresent() {
        var request = new InstallChainTemplateRequest("proj-123", "My Chain");

        Set<ConstraintViolation<InstallChainTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_projectIdIsNull() {
        var request = new InstallChainTemplateRequest(null, "My Chain");

        Set<ConstraintViolation<InstallChainTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("projectId");
    }

    @Test
    void should_failValidation_when_projectIdIsBlank() {
        var request = new InstallChainTemplateRequest("   ", "My Chain");

        Set<ConstraintViolation<InstallChainTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("projectId");
    }

    @Test
    void should_failValidation_when_projectIdIsEmpty() {
        var request = new InstallChainTemplateRequest("", "My Chain");

        Set<ConstraintViolation<InstallChainTemplateRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("projectId");
    }

    @Test
    void should_beEqual_when_bothFieldsMatch() {
        var a = new InstallChainTemplateRequest("proj-123", "My Chain");
        var b = new InstallChainTemplateRequest("proj-123", "My Chain");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_projectIdDiffers() {
        var a = new InstallChainTemplateRequest("proj-123", "My Chain");
        var b = new InstallChainTemplateRequest("proj-456", "My Chain");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_chainNameDiffers() {
        var a = new InstallChainTemplateRequest("proj-123", "Chain A");
        var b = new InstallChainTemplateRequest("proj-123", "Chain B");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new InstallChainTemplateRequest("proj-123", "My Chain");

        assertThat(request).isNotEqualTo(null);
    }

    @Test
    void should_containFieldsInToString() {
        var request = new InstallChainTemplateRequest("proj-123", "My Chain");

        assertThat(request.toString()).contains("proj-123");
        assertThat(request.toString()).contains("My Chain");
    }
}
