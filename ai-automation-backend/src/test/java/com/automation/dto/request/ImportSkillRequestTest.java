package com.automation.dto.request;

import com.automation.entity.SkillCategory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ImportSkillRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    // ── ImportSkillRequest field access ──────────────────────────────────────

    @Test
    void should_returnAllFields_when_constructedWithValidData() {
        var capabilities = List.of(new ImportSkillRequest.ImportCapabilityRequest(
                "cap-name", "cap-desc", "com.example.Handler",
                Map.of("in", "schema"), Map.of("out", "schema"), true, false));
        var request = new ImportSkillRequest(
                "My Skill", "A description", "icon.svg", SkillCategory.CODE,
                "AI context", Map.of("key", "value"), "1.0.0", capabilities);

        assertThat(request.name()).isEqualTo("My Skill");
        assertThat(request.description()).isEqualTo("A description");
        assertThat(request.icon()).isEqualTo("icon.svg");
        assertThat(request.category()).isEqualTo(SkillCategory.CODE);
        assertThat(request.aiContext()).isEqualTo("AI context");
        assertThat(request.configSchema()).containsEntry("key", "value");
        assertThat(request.version()).isEqualTo("1.0.0");
        assertThat(request.capabilities()).hasSize(1);
    }

    // ── ImportSkillRequest validation ─────────────────────────────────────────

    @Test
    void should_passValidation_when_requiredFieldsArePresent() {
        var request = new ImportSkillRequest(
                "My Skill", null, null, SkillCategory.DATA,
                null, null, null, null);

        Set<ConstraintViolation<ImportSkillRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_passValidation_when_allOptionalFieldsAreNull() {
        var request = new ImportSkillRequest(
                "name", null, null, SkillCategory.DOCUMENT,
                null, null, null, null);

        Set<ConstraintViolation<ImportSkillRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_nameIsNull() {
        var request = new ImportSkillRequest(
                null, "desc", null, SkillCategory.BUSINESS,
                null, null, null, null);

        Set<ConstraintViolation<ImportSkillRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    void should_failValidation_when_nameIsBlank() {
        var request = new ImportSkillRequest(
                "   ", "desc", null, SkillCategory.RESEARCH,
                null, null, null, null);

        Set<ConstraintViolation<ImportSkillRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    void should_failValidation_when_categoryIsNull() {
        var request = new ImportSkillRequest(
                "My Skill", null, null, null,
                null, null, null, null);

        Set<ConstraintViolation<ImportSkillRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("category");
    }

    // ── ImportSkillRequest equality / toString ────────────────────────────────

    @Test
    void should_beEqual_when_allFieldsMatch() {
        var a = new ImportSkillRequest("name", "desc", null, SkillCategory.CONTENT, null, null, "1.0", null);
        var b = new ImportSkillRequest("name", "desc", null, SkillCategory.CONTENT, null, null, "1.0", null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_namesDiffer() {
        var a = new ImportSkillRequest("name-a", null, null, SkillCategory.CODE, null, null, null, null);
        var b = new ImportSkillRequest("name-b", null, null, SkillCategory.CODE, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldsInToString() {
        var request = new ImportSkillRequest(
                "My Skill", null, null, SkillCategory.PRODUCTIVITY, null, null, null, null);

        assertThat(request.toString()).contains("name");
        assertThat(request.toString()).contains("My Skill");
        assertThat(request.toString()).contains("category");
    }

    // ── ImportCapabilityRequest field access ──────────────────────────────────

    @Test
    void should_returnAllCapabilityFields_when_constructed() {
        var cap = new ImportSkillRequest.ImportCapabilityRequest(
                "cap-name", "cap-desc", "com.example.Handler",
                Map.of("inKey", 1), Map.of("outKey", 2), true, false);

        assertThat(cap.name()).isEqualTo("cap-name");
        assertThat(cap.description()).isEqualTo("cap-desc");
        assertThat(cap.handlerClass()).isEqualTo("com.example.Handler");
        assertThat(cap.inputSchema()).containsEntry("inKey", 1);
        assertThat(cap.outputSchema()).containsEntry("outKey", 2);
        assertThat(cap.async()).isTrue();
    }

    @Test
    void should_returnFalse_when_asyncIsNotSet() {
        var cap = new ImportSkillRequest.ImportCapabilityRequest(
                "cap-name", null, null, null, null, false, false);

        assertThat(cap.async()).isFalse();
    }

    // ── ImportCapabilityRequest validation ────────────────────────────────────

    @Test
    void should_passCapabilityValidation_when_nameIsPresent() {
        var cap = new ImportSkillRequest.ImportCapabilityRequest(
                "cap-name", null, null, null, null, false, false);

        Set<ConstraintViolation<ImportSkillRequest.ImportCapabilityRequest>> violations = validator.validate(cap);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failCapabilityValidation_when_nameIsNull() {
        var cap = new ImportSkillRequest.ImportCapabilityRequest(
                null, "desc", null, null, null, false, false);

        Set<ConstraintViolation<ImportSkillRequest.ImportCapabilityRequest>> violations = validator.validate(cap);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    void should_failCapabilityValidation_when_nameIsBlank() {
        var cap = new ImportSkillRequest.ImportCapabilityRequest(
                "   ", "desc", null, null, null, false, false);

        Set<ConstraintViolation<ImportSkillRequest.ImportCapabilityRequest>> violations = validator.validate(cap);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    // ── ImportCapabilityRequest equality / toString ───────────────────────────

    @Test
    void should_beEqual_when_capabilityFieldsMatch() {
        var a = new ImportSkillRequest.ImportCapabilityRequest("name", "desc", null, null, null, true, false);
        var b = new ImportSkillRequest.ImportCapabilityRequest("name", "desc", null, null, null, true, false);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_capabilityNamesDiffer() {
        var a = new ImportSkillRequest.ImportCapabilityRequest("name-a", null, null, null, null, false, false);
        var b = new ImportSkillRequest.ImportCapabilityRequest("name-b", null, null, null, null, false, false);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containCapabilityFieldsInToString() {
        var cap = new ImportSkillRequest.ImportCapabilityRequest(
                "cap-name", null, null, null, null, false, false);

        assertThat(cap.toString()).contains("name");
        assertThat(cap.toString()).contains("cap-name");
    }
}
