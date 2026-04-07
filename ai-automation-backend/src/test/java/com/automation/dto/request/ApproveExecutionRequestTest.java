package com.automation.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApproveExecutionRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void should_setAnswer_when_constructedWithValue() {
        var request = new ApproveExecutionRequest("Yes");

        assertThat(request.answer()).isEqualTo("Yes");
    }

    @Test
    void should_passValidation_when_answerIsPresent() {
        var request = new ApproveExecutionRequest("Yes");

        Set<ConstraintViolation<ApproveExecutionRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_answerIsNull() {
        var request = new ApproveExecutionRequest(null);

        Set<ConstraintViolation<ApproveExecutionRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("answer");
    }

    @Test
    void should_failValidation_when_answerIsBlank() {
        var request = new ApproveExecutionRequest("   ");

        Set<ConstraintViolation<ApproveExecutionRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("answer");
    }

    @Test
    void should_beEqual_when_answerMatches() {
        var a = new ApproveExecutionRequest("Yes");
        var b = new ApproveExecutionRequest("Yes");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_containAnswerInToString() {
        var request = new ApproveExecutionRequest("Yes");

        assertThat(request.toString()).contains("answer=Yes");
    }
}
