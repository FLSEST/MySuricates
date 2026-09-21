package com.automation.dto.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ModuleToggleRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void should_passValidation_when_enabledIsTrueOrFalse() {
        assertThat(validator.validate(new ModuleToggleRequest(true))).isEmpty();
        assertThat(validator.validate(new ModuleToggleRequest(false))).isEmpty();
    }

    @Test
    void should_failValidation_when_enabledIsNull() {
        // Boolean on purpose: a missing field must be rejected, not silently read as "off"
        Set<ConstraintViolation<ModuleToggleRequest>> violations = validator.validate(new ModuleToggleRequest(null));

        assertThat(violations).singleElement()
                .satisfies(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("enabled"));
    }

    @Test
    void should_deserializeMissingFieldAsNull_soValidationCatchesIt() throws Exception {
        ModuleToggleRequest request = mapper.readValue("{}", ModuleToggleRequest.class);

        assertThat(request.enabled()).isNull();
        assertThat(validator.validate(request)).hasSize(1);
    }

    @Test
    void should_roundTripThroughJson() throws Exception {
        String json = mapper.writeValueAsString(new ModuleToggleRequest(false));

        assertThat(json).isEqualTo("{\"enabled\":false}");
        assertThat(mapper.readValue(json, ModuleToggleRequest.class)).isEqualTo(new ModuleToggleRequest(false));
    }
}
