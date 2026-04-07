package com.automation.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ActivateSkillsRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void should_returnSkillIds_when_constructedWithValidList() {
        var request = new ActivateSkillsRequest(List.of("skill-1", "skill-2"));

        assertThat(request.skillIds()).containsExactly("skill-1", "skill-2");
    }

    @Test
    void should_passValidation_when_skillIdsIsNonEmpty() {
        var request = new ActivateSkillsRequest(List.of("skill-1"));

        Set<ConstraintViolation<ActivateSkillsRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_skillIdsIsNull() {
        var request = new ActivateSkillsRequest(null);

        Set<ConstraintViolation<ActivateSkillsRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("skillIds");
    }

    @Test
    void should_failValidation_when_skillIdsIsEmpty() {
        var request = new ActivateSkillsRequest(Collections.emptyList());

        Set<ConstraintViolation<ActivateSkillsRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("skillIds");
    }

    @Test
    void should_beEqual_when_skillIdsMatch() {
        var a = new ActivateSkillsRequest(List.of("skill-1", "skill-2"));
        var b = new ActivateSkillsRequest(List.of("skill-1", "skill-2"));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_skillIdsDiffer() {
        var a = new ActivateSkillsRequest(List.of("skill-1"));
        var b = new ActivateSkillsRequest(List.of("skill-2"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containSkillIdsInToString() {
        var request = new ActivateSkillsRequest(List.of("skill-1"));

        assertThat(request.toString()).contains("skillIds");
        assertThat(request.toString()).contains("skill-1");
    }
}
