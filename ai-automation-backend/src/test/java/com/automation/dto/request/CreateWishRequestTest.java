package com.automation.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateWishRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void should_returnAllFields_when_constructedWithAllValues() {
        var request = new CreateWishRequest("Build a feature", "proj-1", 5, List.of("wf-1", "wf-2"), null);

        assertThat(request.text()).isEqualTo("Build a feature");
        assertThat(request.projectId()).isEqualTo("proj-1");
        assertThat(request.maxIterations()).isEqualTo(5);
        assertThat(request.referenceWorkflowIds()).containsExactly("wf-1", "wf-2");
    }

    @Test
    void should_allowNullMaxIterations_when_notProvided() {
        var request = new CreateWishRequest("Build a feature", "proj-1", null, List.of("wf-1"), null);

        assertThat(request.maxIterations()).isNull();
    }

    @Test
    void should_returnNull_when_referenceWorkflowIdsIsNull() {
        var request = new CreateWishRequest("Build a feature", "proj-1", 3, null, null);

        assertThat(request.referenceWorkflowIds()).isNull();
    }

    @Test
    void should_returnEmptyList_when_referenceWorkflowIdsIsEmpty() {
        var request = new CreateWishRequest("Build a feature", "proj-1", 3, Collections.emptyList(), null);

        assertThat(request.referenceWorkflowIds()).isEmpty();
    }

    @Test
    void should_passValidation_when_allRequiredFieldsPresent() {
        var request = new CreateWishRequest("Build a feature", "proj-1", 5, List.of("wf-1"), null);

        Set<ConstraintViolation<CreateWishRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_textIsNull() {
        var request = new CreateWishRequest(null, "proj-1", 5, List.of(), null);

        Set<ConstraintViolation<CreateWishRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("text");
    }

    @Test
    void should_failValidation_when_textIsBlank() {
        var request = new CreateWishRequest("   ", "proj-1", 5, List.of(), null);

        Set<ConstraintViolation<CreateWishRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("text");
    }

    @Test
    void should_failValidation_when_projectIdIsNull() {
        var request = new CreateWishRequest("Build a feature", null, 5, List.of(), null);

        Set<ConstraintViolation<CreateWishRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("projectId");
    }

    @Test
    void should_failValidation_when_projectIdIsBlank() {
        var request = new CreateWishRequest("Build a feature", "  ", 5, List.of(), null);

        Set<ConstraintViolation<CreateWishRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("projectId");
    }

    @Test
    void should_beEqual_when_allFieldsMatch() {
        var a = new CreateWishRequest("Build a feature", "proj-1", 5, List.of("wf-1"), null);
        var b = new CreateWishRequest("Build a feature", "proj-1", 5, List.of("wf-1"), null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_textDiffers() {
        var a = new CreateWishRequest("Build a feature", "proj-1", 5, List.of("wf-1"), null);
        var b = new CreateWishRequest("Fix a bug", "proj-1", 5, List.of("wf-1"), null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_projectIdDiffers() {
        var a = new CreateWishRequest("Build a feature", "proj-1", 5, List.of("wf-1"), null);
        var b = new CreateWishRequest("Build a feature", "proj-2", 5, List.of("wf-1"), null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_maxIterationsDiffers() {
        var a = new CreateWishRequest("Build a feature", "proj-1", 5, List.of("wf-1"), null);
        var b = new CreateWishRequest("Build a feature", "proj-1", 10, List.of("wf-1"), null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_referenceWorkflowIdsDiffer() {
        var a = new CreateWishRequest("Build a feature", "proj-1", 5, List.of("wf-1"), null);
        var b = new CreateWishRequest("Build a feature", "proj-1", 5, List.of("wf-2"), null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new CreateWishRequest("Build a feature", "proj-1", 5, List.of("wf-1"), null);

        assertThat(request).isNotEqualTo(null);
    }

    @Test
    void should_containFieldNamesInToString() {
        var request = new CreateWishRequest("Build a feature", "proj-1", 5, List.of("wf-1"), null);

        assertThat(request.toString()).contains("text");
        assertThat(request.toString()).contains("projectId");
        assertThat(request.toString()).contains("maxIterations");
        assertThat(request.toString()).contains("referenceWorkflowIds");
    }

    @Test
    void should_containValuesInToString() {
        var request = new CreateWishRequest("Build a feature", "proj-1", 5, List.of("wf-1"), null);

        assertThat(request.toString()).contains("Build a feature");
        assertThat(request.toString()).contains("proj-1");
        assertThat(request.toString()).contains("5");
        assertThat(request.toString()).contains("wf-1");
    }
}
