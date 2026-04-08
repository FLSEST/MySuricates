package com.automation.dto.request;

import com.automation.entity.Todo;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateTodoStatusRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void should_setFields_when_constructedWithValidStatusAndSessionId() {
        var request = new UpdateTodoStatusRequest(Todo.Status.DONE, "session-123");

        assertThat(request.status()).isEqualTo(Todo.Status.DONE);
        assertThat(request.sessionId()).isEqualTo("session-123");
    }

    @Test
    void should_allowNullSessionId_when_constructed() {
        var request = new UpdateTodoStatusRequest(Todo.Status.IN_PROGRESS, null);

        assertThat(request.status()).isEqualTo(Todo.Status.IN_PROGRESS);
        assertThat(request.sessionId()).isNull();
    }

    @Test
    void should_passValidation_when_statusIsPresent() {
        var request = new UpdateTodoStatusRequest(Todo.Status.OPEN, "session-456");

        Set<ConstraintViolation<UpdateTodoStatusRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_passValidation_when_statusIsPresentAndSessionIdIsNull() {
        var request = new UpdateTodoStatusRequest(Todo.Status.BACKLOG, null);

        Set<ConstraintViolation<UpdateTodoStatusRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_statusIsNull() {
        var request = new UpdateTodoStatusRequest(null, "session-789");

        Set<ConstraintViolation<UpdateTodoStatusRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("status");
    }

    @Test
    void should_beEqual_when_fieldsMatch() {
        var a = new UpdateTodoStatusRequest(Todo.Status.DONE, "session-123");
        var b = new UpdateTodoStatusRequest(Todo.Status.DONE, "session-123");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_statusDiffers() {
        var a = new UpdateTodoStatusRequest(Todo.Status.DONE, "session-123");
        var b = new UpdateTodoStatusRequest(Todo.Status.OPEN, "session-123");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_sessionIdDiffers() {
        var a = new UpdateTodoStatusRequest(Todo.Status.DONE, "session-123");
        var b = new UpdateTodoStatusRequest(Todo.Status.DONE, "session-456");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldsInToString() {
        var request = new UpdateTodoStatusRequest(Todo.Status.DONE, "session-123");

        assertThat(request.toString()).contains("status=DONE");
        assertThat(request.toString()).contains("sessionId=session-123");
    }
}
