package com.automation.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateWebhookRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void should_returnWorkflowId_when_constructedWithValidValues() {
        var request = new CreateWebhookRequest("wf-123", "My webhook", Map.of("key", "value"));

        assertThat(request.workflowId()).isEqualTo("wf-123");
        assertThat(request.description()).isEqualTo("My webhook");
        assertThat(request.defaultContext()).containsEntry("key", "value");
    }

    @Test
    void should_allowNullDescription_when_constructedWithoutDescription() {
        var request = new CreateWebhookRequest("wf-123", null, Map.of("env", "test"));

        assertThat(request.workflowId()).isEqualTo("wf-123");
        assertThat(request.description()).isNull();
        assertThat(request.defaultContext()).containsEntry("env", "test");
    }

    @Test
    void should_allowNullDefaultContext_when_constructedWithoutContext() {
        var request = new CreateWebhookRequest("wf-123", "desc", null);

        assertThat(request.workflowId()).isEqualTo("wf-123");
        assertThat(request.description()).isEqualTo("desc");
        assertThat(request.defaultContext()).isNull();
    }

    @Test
    void should_allowAllOptionalFieldsNull_when_onlyWorkflowIdProvided() {
        var request = new CreateWebhookRequest("wf-123", null, null);

        assertThat(request.workflowId()).isEqualTo("wf-123");
        assertThat(request.description()).isNull();
        assertThat(request.defaultContext()).isNull();
    }

    @Test
    void should_passValidation_when_workflowIdIsNonBlank() {
        var request = new CreateWebhookRequest("wf-123", "desc", Map.of("key", "val"));

        Set<ConstraintViolation<CreateWebhookRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_workflowIdIsNull() {
        var request = new CreateWebhookRequest(null, "desc", Map.of("key", "val"));

        Set<ConstraintViolation<CreateWebhookRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("workflowId");
    }

    @Test
    void should_failValidation_when_workflowIdIsEmpty() {
        var request = new CreateWebhookRequest("", "desc", null);

        Set<ConstraintViolation<CreateWebhookRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("workflowId");
    }

    @Test
    void should_failValidation_when_workflowIdIsBlank() {
        var request = new CreateWebhookRequest("   ", "desc", null);

        Set<ConstraintViolation<CreateWebhookRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("workflowId");
    }

    @Test
    void should_beEqual_when_allFieldsMatch() {
        var context = Map.<String, Object>of("key", "value");
        var a = new CreateWebhookRequest("wf-1", "desc", context);
        var b = new CreateWebhookRequest("wf-1", "desc", context);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_workflowIdDiffers() {
        var a = new CreateWebhookRequest("wf-1", "desc", null);
        var b = new CreateWebhookRequest("wf-2", "desc", null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldsInToString() {
        var request = new CreateWebhookRequest("wf-123", "my hook", Map.of("env", "prod"));

        assertThat(request.toString()).contains("wf-123");
        assertThat(request.toString()).contains("my hook");
        assertThat(request.toString()).contains("env");
    }
}
