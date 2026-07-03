package com.automation.dto.request;

import com.automation.entity.Todo;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BulkUpdateTodoStatusRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    // --- Getter / Record semantics ---

    @Test
    void should_returnIds_when_constructedWithIds() {
        var request = new BulkUpdateTodoStatusRequest(List.of("id-1", "id-2"), Todo.Status.DONE);

        assertThat(request.ids()).containsExactly("id-1", "id-2");
    }

    @Test
    void should_returnStatus_when_constructed() {
        var request = new BulkUpdateTodoStatusRequest(List.of("id-1"), Todo.Status.ARCHIVED);

        assertThat(request.status()).isEqualTo(Todo.Status.ARCHIVED);
    }

    // --- Validation: ids ---

    @Test
    void should_passValidation_when_idsNonEmptyAndStatusNotNull() {
        var request = new BulkUpdateTodoStatusRequest(List.of("id-1"), Todo.Status.DONE);

        Set<ConstraintViolation<BulkUpdateTodoStatusRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_idsIsNull() {
        var request = new BulkUpdateTodoStatusRequest(null, Todo.Status.DONE);

        Set<ConstraintViolation<BulkUpdateTodoStatusRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("ids");
    }

    @Test
    void should_failValidation_when_idsIsEmpty() {
        var request = new BulkUpdateTodoStatusRequest(Collections.emptyList(), Todo.Status.DONE);

        Set<ConstraintViolation<BulkUpdateTodoStatusRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("ids");
    }

    // --- Validation: status ---

    @Test
    void should_failValidation_when_statusIsNull() {
        var request = new BulkUpdateTodoStatusRequest(List.of("id-1"), null);

        Set<ConstraintViolation<BulkUpdateTodoStatusRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("status");
    }

    @Test
    void should_failValidation_when_bothIdsEmptyAndStatusNull() {
        var request = new BulkUpdateTodoStatusRequest(Collections.emptyList(), null);

        Set<ConstraintViolation<BulkUpdateTodoStatusRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(2);
    }

    // --- All status values ---

    @Test
    void should_passValidation_for_allStatusValues() {
        for (Todo.Status status : Todo.Status.values()) {
            var request = new BulkUpdateTodoStatusRequest(List.of("id-1"), status);

            Set<ConstraintViolation<BulkUpdateTodoStatusRequest>> violations = validator.validate(request);

            assertThat(violations).as("status=%s should pass validation", status).isEmpty();
        }
    }

    // --- equals / hashCode ---

    @Test
    void should_beEqual_when_idsAndStatusMatch() {
        var a = new BulkUpdateTodoStatusRequest(List.of("id-1", "id-2"), Todo.Status.DONE);
        var b = new BulkUpdateTodoStatusRequest(List.of("id-1", "id-2"), Todo.Status.DONE);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_idsDiffer() {
        var a = new BulkUpdateTodoStatusRequest(List.of("id-1"), Todo.Status.DONE);
        var b = new BulkUpdateTodoStatusRequest(List.of("id-2"), Todo.Status.DONE);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_statusDiffers() {
        var a = new BulkUpdateTodoStatusRequest(List.of("id-1"), Todo.Status.DONE);
        var b = new BulkUpdateTodoStatusRequest(List.of("id-1"), Todo.Status.ARCHIVED);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new BulkUpdateTodoStatusRequest(List.of("id-1"), Todo.Status.DONE);

        assertThat(request).isNotEqualTo(null);
    }

    @Test
    void should_notBeEqual_when_comparedToDifferentType() {
        var request = new BulkUpdateTodoStatusRequest(List.of("id-1"), Todo.Status.DONE);

        assertThat(request).isNotEqualTo("not a request");
    }

    // --- toString ---

    @Test
    void should_containIdsInToString() {
        var request = new BulkUpdateTodoStatusRequest(List.of("id-1"), Todo.Status.DONE);

        assertThat(request.toString()).contains("ids");
        assertThat(request.toString()).contains("id-1");
    }

    @Test
    void should_containStatusInToString() {
        var request = new BulkUpdateTodoStatusRequest(List.of("id-1"), Todo.Status.ARCHIVED);

        assertThat(request.toString()).contains("ARCHIVED");
    }
}
