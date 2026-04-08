package com.automation.dto.request;

import com.automation.entity.ProjectType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateProjectRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void should_setAllFields_when_constructedWithValues() {
        Map<String, Object> config = Map.of("key", "value");
        var request = new CreateProjectRequest("My Project", "A subtitle", "A description",
                ProjectType.CODE, "/home/user/project", config, "AI context");

        assertThat(request.name()).isEqualTo("My Project");
        assertThat(request.subtitle()).isEqualTo("A subtitle");
        assertThat(request.description()).isEqualTo("A description");
        assertThat(request.type()).isEqualTo(ProjectType.CODE);
        assertThat(request.path()).isEqualTo("/home/user/project");
        assertThat(request.config()).isEqualTo(config);
        assertThat(request.aiContext()).isEqualTo("AI context");
    }

    @Test
    void should_passValidation_when_allRequiredFieldsPresent() {
        var request = new CreateProjectRequest("My Project", null, null,
                ProjectType.CODE, "/home/user/project", null, null);

        Set<ConstraintViolation<CreateProjectRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_nameIsNull() {
        var request = new CreateProjectRequest(null, null, null,
                ProjectType.CODE, "/home/user/project", null, null);

        Set<ConstraintViolation<CreateProjectRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    void should_failValidation_when_nameIsBlank() {
        var request = new CreateProjectRequest("   ", null, null,
                ProjectType.CODE, "/home/user/project", null, null);

        Set<ConstraintViolation<CreateProjectRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    void should_failValidation_when_typeIsNull() {
        var request = new CreateProjectRequest("My Project", null, null,
                null, "/home/user/project", null, null);

        Set<ConstraintViolation<CreateProjectRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("type");
    }

    @Test
    void should_failValidation_when_pathIsNull() {
        var request = new CreateProjectRequest("My Project", null, null,
                ProjectType.CODE, null, null, null);

        Set<ConstraintViolation<CreateProjectRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("path");
    }

    @Test
    void should_failValidation_when_pathIsBlank() {
        var request = new CreateProjectRequest("My Project", null, null,
                ProjectType.CODE, "  ", null, null);

        Set<ConstraintViolation<CreateProjectRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("path");
    }

    @Test
    void should_failValidation_when_multipleRequiredFieldsMissing() {
        var request = new CreateProjectRequest(null, null, null,
                null, null, null, null);

        Set<ConstraintViolation<CreateProjectRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(3);
        Set<String> paths = Set.of("name", "type", "path");
        violations.forEach(v ->
                assertThat(paths).contains(v.getPropertyPath().toString()));
    }

    @Test
    void should_beEqual_when_allFieldsMatch() {
        Map<String, Object> config = Map.of("key", "value");
        var a = new CreateProjectRequest("Project", "sub", "desc",
                ProjectType.CODE, "/path", config, "ai");
        var b = new CreateProjectRequest("Project", "sub", "desc",
                ProjectType.CODE, "/path", config, "ai");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_namesDiffer() {
        var a = new CreateProjectRequest("Project A", null, null,
                ProjectType.CODE, "/path", null, null);
        var b = new CreateProjectRequest("Project B", null, null,
                ProjectType.CODE, "/path", null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_typesDiffer() {
        var a = new CreateProjectRequest("Project", null, null,
                ProjectType.CODE, "/path", null, null);
        var b = new CreateProjectRequest("Project", null, null,
                ProjectType.CONTENT, "/path", null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldsInToString() {
        var request = new CreateProjectRequest("My Project", "subtitle", null,
                ProjectType.CODE, "/home/user/project", null, null);

        assertThat(request.toString()).contains("My Project");
        assertThat(request.toString()).contains("CODE");
        assertThat(request.toString()).contains("/home/user/project");
    }
}
