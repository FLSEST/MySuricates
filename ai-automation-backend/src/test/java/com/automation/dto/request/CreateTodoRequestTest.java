package com.automation.dto.request;

import com.automation.entity.Todo;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateTodoRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    // --- Record accessors ---

    @Test
    void should_returnAllFields_when_constructedWithAllArguments() {
        var tags = List.of("backend", "bug");
        var request = new CreateTodoRequest(
                "Fix login bug",
                "Users cannot log in after update",
                Todo.Status.OPEN,
                Todo.Priority.HIGH,
                tags,
                "project-123",
                "workflow-456",
                Todo.Source.AI,
                "claude-code",
                "session-456",
                "run-789",
                "WORKFLOW",
                "ref-001",
                "2026-12-31"
        );

        assertThat(request.title()).isEqualTo("Fix login bug");
        assertThat(request.description()).isEqualTo("Users cannot log in after update");
        assertThat(request.status()).isEqualTo(Todo.Status.OPEN);
        assertThat(request.priority()).isEqualTo(Todo.Priority.HIGH);
        assertThat(request.tags()).containsExactly("backend", "bug");
        assertThat(request.projectId()).isEqualTo("project-123");
        assertThat(request.linkedWorkflowId()).isEqualTo("workflow-456");
        assertThat(request.source()).isEqualTo(Todo.Source.AI);
        assertThat(request.sourceAgent()).isEqualTo("claude-code");
        assertThat(request.sessionId()).isEqualTo("session-456");
        assertThat(request.workflowRunId()).isEqualTo("run-789");
        assertThat(request.referenceType()).isEqualTo("WORKFLOW");
        assertThat(request.referenceId()).isEqualTo("ref-001");
        assertThat(request.dueDate()).isEqualTo("2026-12-31");
    }

    @Test
    void should_returnNullForOptionalFields_when_onlyTitleProvided() {
        var request = new CreateTodoRequest(
                "Minimal todo", null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        assertThat(request.title()).isEqualTo("Minimal todo");
        assertThat(request.description()).isNull();
        assertThat(request.status()).isNull();
        assertThat(request.priority()).isNull();
        assertThat(request.tags()).isNull();
        assertThat(request.projectId()).isNull();
        assertThat(request.linkedWorkflowId()).isNull();
        assertThat(request.source()).isNull();
        assertThat(request.sourceAgent()).isNull();
        assertThat(request.sessionId()).isNull();
        assertThat(request.workflowRunId()).isNull();
        assertThat(request.referenceType()).isNull();
        assertThat(request.referenceId()).isNull();
        assertThat(request.dueDate()).isNull();
    }

    // --- Bean validation: title (@NotBlank) ---

    @Test
    void should_passValidation_when_titleIsValid() {
        var request = new CreateTodoRequest(
                "Valid title", null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        Set<ConstraintViolation<CreateTodoRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_titleIsNull() {
        var request = new CreateTodoRequest(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        Set<ConstraintViolation<CreateTodoRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("title");
    }

    @Test
    void should_failValidation_when_titleIsEmpty() {
        var request = new CreateTodoRequest(
                "", null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        Set<ConstraintViolation<CreateTodoRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("title");
    }

    @Test
    void should_failValidation_when_titleIsBlank() {
        var request = new CreateTodoRequest(
                "   ", null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        Set<ConstraintViolation<CreateTodoRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("title");
    }

    // --- Validation passes with all optional fields null ---

    @Test
    void should_passValidation_when_allOptionalFieldsAreNull() {
        var request = new CreateTodoRequest(
                "Title only", null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        Set<ConstraintViolation<CreateTodoRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    // --- Validation passes with fully populated request ---

    @Test
    void should_passValidation_when_fullyPopulated() {
        var request = new CreateTodoRequest(
                "Full todo",
                "Description",
                Todo.Status.IN_PROGRESS,
                Todo.Priority.MEDIUM,
                List.of("feature"),
                "proj-1",
                "wf-1",
                Todo.Source.USER,
                "agent",
                "sess",
                "run",
                "TYPE",
                "id",
                "2026-06-15"
        );

        Set<ConstraintViolation<CreateTodoRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    // --- Optional enum fields ---

    @Test
    void should_acceptAllStatusValues() {
        for (Todo.Status status : Todo.Status.values()) {
        var request = new CreateTodoRequest(
                    "Title", null, status, null, null, null, null, null, null, null, null, null, null, null
            );
            assertThat(request.status()).isEqualTo(status);
            assertThat(validator.validate(request)).isEmpty();
        }
    }

    @Test
    void should_acceptAllPriorityValues() {
        for (Todo.Priority priority : Todo.Priority.values()) {
        var request = new CreateTodoRequest(
                    "Title", null, null, priority, null, null, null, null, null, null, null, null, null, null
            );
            assertThat(request.priority()).isEqualTo(priority);
            assertThat(validator.validate(request)).isEmpty();
        }
    }

    @Test
    void should_acceptAllSourceValues() {
        for (Todo.Source source : Todo.Source.values()) {
        var request = new CreateTodoRequest(
                    "Title", null, null, null, null, null, null, source, null, null, null, null, null, null
            );
            assertThat(request.source()).isEqualTo(source);
            assertThat(validator.validate(request)).isEmpty();
        }
    }

    // --- Tags list ---

    @Test
    void should_returnEmptyList_when_tagsIsEmpty() {
        var request = new CreateTodoRequest(
                "Title", null, null, null, List.of(), null, null, null, null, null, null, null, null, null
        );

        assertThat(request.tags()).isEmpty();
    }

    @Test
    void should_returnMultipleTags_when_tagsHasMultipleEntries() {
        var tags = List.of("backend", "bug", "urgent");
        var request = new CreateTodoRequest(
                "Title", null, null, null, tags, null, null, null, null, null, null, null, null, null
        );

        assertThat(request.tags()).containsExactly("backend", "bug", "urgent");
    }

    // --- equals / hashCode ---

    @Test
    void should_beEqual_when_allFieldsMatch() {
        var tags = List.of("tag1");
        var a = new CreateTodoRequest(
                "Title", "Desc", Todo.Status.OPEN, Todo.Priority.LOW, tags,
                "proj", "wf-1", Todo.Source.WORKFLOW, "agent", "sess", "run", "type", "ref", "2026-01-01"
        );
        var b = new CreateTodoRequest(
                "Title", "Desc", Todo.Status.OPEN, Todo.Priority.LOW, tags,
                "proj", "wf-1", Todo.Source.WORKFLOW, "agent", "sess", "run", "type", "ref", "2026-01-01"
        );

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_titleDiffers() {
        var a = new CreateTodoRequest(
                "Title A", null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        var b = new CreateTodoRequest(
                "Title B", null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new CreateTodoRequest(
                "Title", null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        assertThat(request).isNotEqualTo(null);
    }

    @Test
    void should_notBeEqual_when_comparedToDifferentType() {
        var request = new CreateTodoRequest(
                "Title", null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        assertThat(request).isNotEqualTo("not a request");
    }

    // --- toString ---

    @Test
    void should_containTitle_inToString() {
        var request = new CreateTodoRequest(
                "My Todo Title", null, null, null, null, null, null, null, null, null, null, null, null, null
        );

        assertThat(request.toString()).contains("title");
        assertThat(request.toString()).contains("My Todo Title");
    }

    @Test
    void should_containAllFieldNames_inToString() {
        var request = new CreateTodoRequest(
                "Title", "Desc", Todo.Status.DONE, Todo.Priority.CRITICAL, List.of("t"),
                "proj", "wf-1", Todo.Source.AI, "agent", "sess", "run", "type", "ref", "2026-01-01"
        );

        String str = request.toString();
        assertThat(str).contains("title");
        assertThat(str).contains("description");
        assertThat(str).contains("status");
        assertThat(str).contains("priority");
        assertThat(str).contains("tags");
        assertThat(str).contains("projectId");
        assertThat(str).contains("linkedWorkflowId");
        assertThat(str).contains("source");
        assertThat(str).contains("sourceAgent");
        assertThat(str).contains("sessionId");
        assertThat(str).contains("workflowRunId");
        assertThat(str).contains("referenceType");
        assertThat(str).contains("referenceId");
        assertThat(str).contains("dueDate");
    }
}
