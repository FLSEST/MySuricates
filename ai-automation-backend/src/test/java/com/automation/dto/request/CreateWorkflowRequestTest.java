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

class CreateWorkflowRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    // --- Full construction ---

    @Test
    void should_setAllFields_when_constructedWithAllArgs() {
        var blocks = List.of(BlockDefinition.builder().type("shell").build());
        var skillIds = List.of("skill-1", "skill-2");
        var metadata = Map.<String, Object>of("autoTodo", true);
        var context = Map.<String, Object>of("env", "prod");

        var request = new CreateWorkflowRequest(
                "project-1", "My Workflow", "A description", "rocket",
                blocks, skillIds, "Do the thing", "claude-opus-4-6", "CLAUDE",
                "template-1", metadata, context, "1.0");

        assertThat(request.projectId()).isEqualTo("project-1");
        assertThat(request.name()).isEqualTo("My Workflow");
        assertThat(request.description()).isEqualTo("A description");
        assertThat(request.icon()).isEqualTo("rocket");
        assertThat(request.blocks()).isEqualTo(blocks);
        assertThat(request.activatedSkillIds()).isEqualTo(skillIds);
        assertThat(request.instructions()).isEqualTo("Do the thing");
        assertThat(request.model()).isEqualTo("claude-opus-4-6");
        assertThat(request.provider()).isEqualTo("CLAUDE");
        assertThat(request.templateId()).isEqualTo("template-1");
        assertThat(request.metadata()).isEqualTo(metadata);
        assertThat(request.defaultContext()).isEqualTo(context);
        assertThat(request.version()).isEqualTo("1.0");
    }

    // --- Compact constructor: null normalization ---

    @Test
    void should_normalizeNullBlocks_toEmptyList() {
        var request = new CreateWorkflowRequest(
                "p1", "name", null, null,
                null, List.of("s1"), null, null, null,
                null, null, null, null);

        assertThat(request.blocks()).isEmpty();
    }

    @Test
    void should_normalizeNullActivatedSkillIds_toEmptyList() {
        var request = new CreateWorkflowRequest(
                "p1", "name", null, null,
                List.of(), null, null, null, null,
                null, null, null, null);

        assertThat(request.activatedSkillIds()).isEmpty();
    }

    @Test
    void should_normalizeBothNullLists_toEmptyLists() {
        var request = new CreateWorkflowRequest(
                "p1", "name", null, null,
                null, null, null, null, null,
                null, null, null, null);

        assertThat(request.blocks()).isEmpty();
        assertThat(request.activatedSkillIds()).isEmpty();
    }

    @Test
    void should_preserveNonNullBlocks_when_provided() {
        var blocks = List.of(BlockDefinition.builder().type("simple").build());
        var request = new CreateWorkflowRequest(
                "p1", "name", null, null,
                blocks, null, null, null, null,
                null, null, null, null);

        assertThat(request.blocks()).hasSize(1);
        assertThat(request.blocks().get(0).getType()).isEqualTo("simple");
    }

    @Test
    void should_preserveNonNullSkillIds_when_provided() {
        var skillIds = List.of("skill-a", "skill-b");
        var request = new CreateWorkflowRequest(
                "p1", "name", null, null,
                null, skillIds, null, null, null,
                null, null, null, null);

        assertThat(request.activatedSkillIds()).containsExactly("skill-a", "skill-b");
    }

    // --- Optional fields accept null ---

    @Test
    void should_acceptNullOptionalFields() {
        var request = new CreateWorkflowRequest(
                "p1", "name", null, null,
                null, null, null, null, null,
                null, null, null, null);

        assertThat(request.description()).isNull();
        assertThat(request.icon()).isNull();
        assertThat(request.instructions()).isNull();
        assertThat(request.model()).isNull();
        assertThat(request.provider()).isNull();
        assertThat(request.templateId()).isNull();
        assertThat(request.metadata()).isNull();
        assertThat(request.defaultContext()).isNull();
        assertThat(request.version()).isNull();
    }

    // --- @NotBlank validation ---

    @Test
    void should_passValidation_when_requiredFieldsPresent() {
        var request = new CreateWorkflowRequest(
                "project-1", "My Workflow", null, null,
                null, null, null, null, null,
                null, null, null, null);

        Set<ConstraintViolation<CreateWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_projectIdIsNull() {
        var request = new CreateWorkflowRequest(
                null, "My Workflow", null, null,
                null, null, null, null, null,
                null, null, null, null);

        Set<ConstraintViolation<CreateWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("projectId");
    }

    @Test
    void should_failValidation_when_projectIdIsBlank() {
        var request = new CreateWorkflowRequest(
                "  ", "My Workflow", null, null,
                null, null, null, null, null,
                null, null, null, null);

        Set<ConstraintViolation<CreateWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("projectId");
    }

    @Test
    void should_failValidation_when_nameIsNull() {
        var request = new CreateWorkflowRequest(
                "project-1", null, null, null,
                null, null, null, null, null,
                null, null, null, null);

        Set<ConstraintViolation<CreateWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    void should_failValidation_when_nameIsBlank() {
        var request = new CreateWorkflowRequest(
                "project-1", "", null, null,
                null, null, null, null, null,
                null, null, null, null);

        Set<ConstraintViolation<CreateWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    void should_failValidation_when_bothRequiredFieldsMissing() {
        var request = new CreateWorkflowRequest(
                null, null, null, null,
                null, null, null, null, null,
                null, null, null, null);

        Set<ConstraintViolation<CreateWorkflowRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(2);
        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("projectId", "name");
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var blocks = List.of(BlockDefinition.builder().type("shell").build());
        var a = new CreateWorkflowRequest(
                "p1", "name", "desc", "icon",
                blocks, List.of("s1"), "instr", "model", "provider",
                "tmpl", Map.of("k", "v"), Map.of("c", "d"), "1.0");
        var b = new CreateWorkflowRequest(
                "p1", "name", "desc", "icon",
                blocks, List.of("s1"), "instr", "model", "provider",
                "tmpl", Map.of("k", "v"), Map.of("c", "d"), "1.0");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentName() {
        var a = new CreateWorkflowRequest(
                "p1", "name-a", null, null,
                null, null, null, null, null,
                null, null, null, null);
        var b = new CreateWorkflowRequest(
                "p1", "name-b", null, null,
                null, null, null, null, null,
                null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentProjectId() {
        var a = new CreateWorkflowRequest(
                "p1", "name", null, null,
                null, null, null, null, null,
                null, null, null, null);
        var b = new CreateWorkflowRequest(
                "p2", "name", null, null,
                null, null, null, null, null,
                null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }
}
