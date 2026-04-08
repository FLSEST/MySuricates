package com.automation.dto.request;

import com.automation.entity.SkillCategory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateSkillRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void should_returnFields_when_constructedWithAllValues() {
        var configSchema = Map.<String, Object>of("type", "object");
        var request = new CreateSkillRequest("Test Skill", "A description", "icon-test",
                SkillCategory.CODE, "AI context text", configSchema);

        assertThat(request.name()).isEqualTo("Test Skill");
        assertThat(request.description()).isEqualTo("A description");
        assertThat(request.icon()).isEqualTo("icon-test");
        assertThat(request.category()).isEqualTo(SkillCategory.CODE);
        assertThat(request.aiContext()).isEqualTo("AI context text");
        assertThat(request.configSchema()).isEqualTo(configSchema);
    }

    @Test
    void should_passValidation_when_requiredFieldsArePresent() {
        var request = new CreateSkillRequest("Test Skill", null, null,
                SkillCategory.DOCUMENT, null, null);

        Set<ConstraintViolation<CreateSkillRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_nameIsNull() {
        var request = new CreateSkillRequest(null, "desc", null,
                SkillCategory.CODE, null, null);

        Set<ConstraintViolation<CreateSkillRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    void should_failValidation_when_nameIsBlank() {
        var request = new CreateSkillRequest("   ", "desc", null,
                SkillCategory.CODE, null, null);

        Set<ConstraintViolation<CreateSkillRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    void should_failValidation_when_categoryIsNull() {
        var request = new CreateSkillRequest("Test Skill", null, null,
                null, null, null);

        Set<ConstraintViolation<CreateSkillRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("category");
    }

    @Test
    void should_failValidation_when_nameAndCategoryAreNull() {
        var request = new CreateSkillRequest(null, null, null,
                null, null, null);

        Set<ConstraintViolation<CreateSkillRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(2);
        Set<String> paths = Set.of(
                violations.stream().toList().get(0).getPropertyPath().toString(),
                violations.stream().toList().get(1).getPropertyPath().toString()
        );
        assertThat(paths).containsExactlyInAnyOrder("name", "category");
    }

    @Test
    void should_beEqual_when_allFieldsMatch() {
        var config = Map.<String, Object>of("key", "value");
        var a = new CreateSkillRequest("Skill", "desc", "icon",
                SkillCategory.DATA, "context", config);
        var b = new CreateSkillRequest("Skill", "desc", "icon",
                SkillCategory.DATA, "context", config);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_namesDiffer() {
        var a = new CreateSkillRequest("Skill A", null, null,
                SkillCategory.CODE, null, null);
        var b = new CreateSkillRequest("Skill B", null, null,
                SkillCategory.CODE, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldsInToString() {
        var request = new CreateSkillRequest("My Skill", "desc", "icon",
                SkillCategory.RESEARCH, "ai-ctx", null);

        assertThat(request.toString()).contains("My Skill");
        assertThat(request.toString()).contains("RESEARCH");
    }
}
