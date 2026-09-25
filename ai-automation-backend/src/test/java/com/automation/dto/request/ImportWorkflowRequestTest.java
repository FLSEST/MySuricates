package com.automation.dto.request;

import com.automation.entity.BlockDefinition;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ImportWorkflowRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void should_returnAllFields_when_constructedWithValues() {
        var block = BlockDefinition.builder().type("simple").build();
        var context = Map.<String, Object>of("key", "value");
        var metadata = Map.<String, Object>of("version", "1.0");

        var request = new ImportWorkflowRequest(
                "project-1", "My Workflow", "A description", "icon-star",
                List.of(block), "instructions", "claude-sonnet-4-6", true,
                context, metadata
        );

        assertThat(request.projectId()).isEqualTo("project-1");
        assertThat(request.name()).isEqualTo("My Workflow");
        assertThat(request.description()).isEqualTo("A description");
        assertThat(request.icon()).isEqualTo("icon-star");
        assertThat(request.blocks()).containsExactly(block);
        assertThat(request.instructions()).isEqualTo("instructions");
        assertThat(request.model()).isEqualTo("claude-sonnet-4-6");
        assertThat(request.updateIfExists()).isTrue();
        assertThat(request.defaultContext()).containsEntry("key", "value");
        assertThat(request.metadata()).containsEntry("version", "1.0");
    }

    /** TODO 5e2e73a5: the export file's version label travels with the import; the short form has none. */
    @Test
    void should_carryTheVersionLabel_andLeaveItNullInTheShortForm() {
        var withVersion = new ImportWorkflowRequest(
                null, "Test", null, null, null, null, null, true, null, null, "4.4.0");
        var shortForm = new ImportWorkflowRequest(
                null, "Test", null, null, null, null, null, true, null, null);

        assertThat(withVersion.version()).isEqualTo("4.4.0");
        assertThat(shortForm.version()).isNull();
    }

    @Test
    void should_defaultBlocksToEmptyList_when_blocksIsNull() {
        var request = new ImportWorkflowRequest(
                null, "Test", null, null, null, null, null, null, null, null
        );

        assertThat(request.blocks()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultUpdateIfExistsToFalse_when_null() {
        var request = new ImportWorkflowRequest(
                null, "Test", null, null, null, null, null, null, null, null
        );

        assertThat(request.updateIfExists()).isFalse();
    }

    @Test
    void should_preserveBlocks_when_nonNull() {
        var block = BlockDefinition.builder().type("shell").build();

        var request = new ImportWorkflowRequest(
                null, "Test", null, null, List.of(block), null, null, null, null, null
        );

        assertThat(request.blocks()).hasSize(1);
        assertThat(request.blocks().getFirst().getType()).isEqualTo("shell");
    }

    @Test
    void should_preserveUpdateIfExists_when_true() {
        var request = new ImportWorkflowRequest(
                null, "Test", null, null, null, null, null, true, null, null
        );

        assertThat(request.updateIfExists()).isTrue();
    }

    @Test
    void should_passValidation_when_nameIsPresent() {
        var request = new ImportWorkflowRequest(
                null, "Valid Name", null, null, null, null, null, null, null, null
        );

        Set<ConstraintViolation<ImportWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_nameIsNull() {
        var request = new ImportWorkflowRequest(
                null, null, null, null, null, null, null, null, null, null
        );

        Set<ConstraintViolation<ImportWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    void should_failValidation_when_nameIsBlank() {
        var request = new ImportWorkflowRequest(
                null, "   ", null, null, null, null, null, null, null, null
        );

        Set<ConstraintViolation<ImportWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    void should_beEqual_when_fieldsMatch() {
        var a = new ImportWorkflowRequest(
                "p1", "Name", "desc", null, null, null, null, null, null, null
        );
        var b = new ImportWorkflowRequest(
                "p1", "Name", "desc", null, null, null, null, null, null, null
        );

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_namesDiffer() {
        var a = new ImportWorkflowRequest(
                null, "Name A", null, null, null, null, null, null, null, null
        );
        var b = new ImportWorkflowRequest(
                null, "Name B", null, null, null, null, null, null, null, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containNameInToString() {
        var request = new ImportWorkflowRequest(
                null, "My Workflow", null, null, null, null, null, null, null, null
        );

        assertThat(request.toString()).contains("My Workflow");
    }
}
