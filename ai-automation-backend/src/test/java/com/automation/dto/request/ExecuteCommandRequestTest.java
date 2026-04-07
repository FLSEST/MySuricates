package com.automation.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExecuteCommandRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void should_setCommand_when_constructedWithValue() {
        var request = new ExecuteCommandRequest("ls -la");

        assertThat(request.command()).isEqualTo("ls -la");
    }

    @Test
    void should_passValidation_when_commandIsPresent() {
        var request = new ExecuteCommandRequest("ls -la");

        Set<ConstraintViolation<ExecuteCommandRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_commandIsNull() {
        var request = new ExecuteCommandRequest(null);

        Set<ConstraintViolation<ExecuteCommandRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("command");
    }

    @Test
    void should_failValidation_when_commandIsBlank() {
        var request = new ExecuteCommandRequest("  ");

        Set<ConstraintViolation<ExecuteCommandRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("command");
    }

    @Test
    void should_beEqual_when_commandMatches() {
        var a = new ExecuteCommandRequest("pwd");
        var b = new ExecuteCommandRequest("pwd");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_containCommandInToString() {
        var request = new ExecuteCommandRequest("pwd");

        assertThat(request.toString()).contains("command=pwd");
    }
}
