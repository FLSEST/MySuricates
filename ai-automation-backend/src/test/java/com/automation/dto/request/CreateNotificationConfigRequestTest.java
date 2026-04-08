package com.automation.dto.request;

import com.automation.entity.NotificationConfig.ChannelType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateNotificationConfigRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    // --- Accessor tests ---

    @Test
    void should_returnAllFields_when_fullyPopulated() {
        var request = new CreateNotificationConfigRequest(
                "My Config", ChannelType.WEBHOOK, "https://example.com/hook",
                "wf-123", true, false, true, "Build {{status}}"
        );

        assertThat(request.name()).isEqualTo("My Config");
        assertThat(request.channelType()).isEqualTo(ChannelType.WEBHOOK);
        assertThat(request.webhookUrl()).isEqualTo("https://example.com/hook");
        assertThat(request.workflowId()).isEqualTo("wf-123");
        assertThat(request.triggerOnSuccess()).isTrue();
        assertThat(request.triggerOnFailure()).isFalse();
        assertThat(request.triggerAlways()).isTrue();
        assertThat(request.messageTemplate()).isEqualTo("Build {{status}}");
    }

    @Test
    void should_returnDesktopChannelType_when_constructedWithDesktop() {
        var request = new CreateNotificationConfigRequest(
                "Desktop Alert", ChannelType.DESKTOP, null,
                null, false, false, false, null
        );

        assertThat(request.channelType()).isEqualTo(ChannelType.DESKTOP);
    }

    // --- Null cases for optional fields ---

    @Test
    void should_allowNullWebhookUrl_when_notProvided() {
        var request = new CreateNotificationConfigRequest(
                "Config", ChannelType.DESKTOP, null,
                "wf-1", false, false, false, null
        );

        assertThat(request.webhookUrl()).isNull();
    }

    @Test
    void should_allowNullWorkflowId_when_notProvided() {
        var request = new CreateNotificationConfigRequest(
                "Config", ChannelType.WEBHOOK, "https://hook.example.com",
                null, false, false, false, null
        );

        assertThat(request.workflowId()).isNull();
    }

    @Test
    void should_allowNullMessageTemplate_when_notProvided() {
        var request = new CreateNotificationConfigRequest(
                "Config", ChannelType.WEBHOOK, "https://hook.example.com",
                "wf-1", false, false, false, null
        );

        assertThat(request.messageTemplate()).isNull();
    }

    @Test
    void should_allowAllOptionalFieldsNull_when_onlyRequiredFieldsProvided() {
        var request = new CreateNotificationConfigRequest(
                "Minimal", ChannelType.WEBHOOK, null,
                null, false, false, false, null
        );

        assertThat(request.name()).isEqualTo("Minimal");
        assertThat(request.channelType()).isEqualTo(ChannelType.WEBHOOK);
        assertThat(request.webhookUrl()).isNull();
        assertThat(request.workflowId()).isNull();
        assertThat(request.messageTemplate()).isNull();
        assertThat(request.triggerOnSuccess()).isFalse();
        assertThat(request.triggerOnFailure()).isFalse();
        assertThat(request.triggerAlways()).isFalse();
    }

    // --- Trigger flag combinations ---

    @Test
    void should_setOnlyTriggerOnSuccess_when_successFlagIsTrue() {
        var request = new CreateNotificationConfigRequest(
                "Success Only", ChannelType.WEBHOOK, null,
                null, true, false, false, null
        );

        assertThat(request.triggerOnSuccess()).isTrue();
        assertThat(request.triggerOnFailure()).isFalse();
        assertThat(request.triggerAlways()).isFalse();
    }

    @Test
    void should_setOnlyTriggerOnFailure_when_failureFlagIsTrue() {
        var request = new CreateNotificationConfigRequest(
                "Failure Only", ChannelType.WEBHOOK, null,
                null, false, true, false, null
        );

        assertThat(request.triggerOnSuccess()).isFalse();
        assertThat(request.triggerOnFailure()).isTrue();
        assertThat(request.triggerAlways()).isFalse();
    }

    @Test
    void should_setOnlyTriggerAlways_when_alwaysFlagIsTrue() {
        var request = new CreateNotificationConfigRequest(
                "Always", ChannelType.WEBHOOK, null,
                null, false, false, true, null
        );

        assertThat(request.triggerOnSuccess()).isFalse();
        assertThat(request.triggerOnFailure()).isFalse();
        assertThat(request.triggerAlways()).isTrue();
    }

    @Test
    void should_setSuccessAndFailure_when_bothFlagsAreTrue() {
        var request = new CreateNotificationConfigRequest(
                "Both", ChannelType.WEBHOOK, null,
                null, true, true, false, null
        );

        assertThat(request.triggerOnSuccess()).isTrue();
        assertThat(request.triggerOnFailure()).isTrue();
        assertThat(request.triggerAlways()).isFalse();
    }

    @Test
    void should_setAllTriggerFlags_when_allFlagsAreTrue() {
        var request = new CreateNotificationConfigRequest(
                "All", ChannelType.WEBHOOK, null,
                null, true, true, true, null
        );

        assertThat(request.triggerOnSuccess()).isTrue();
        assertThat(request.triggerOnFailure()).isTrue();
        assertThat(request.triggerAlways()).isTrue();
    }

    // --- Validation: @NotBlank name ---

    @Test
    void should_passValidation_when_nameIsNonBlankAndChannelTypeIsSet() {
        var request = new CreateNotificationConfigRequest(
                "Valid Name", ChannelType.WEBHOOK, null,
                null, false, false, false, null
        );

        Set<ConstraintViolation<CreateNotificationConfigRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    @Test
    void should_failValidation_when_nameIsNull() {
        var request = new CreateNotificationConfigRequest(
                null, ChannelType.WEBHOOK, null,
                null, false, false, false, null
        );

        Set<ConstraintViolation<CreateNotificationConfigRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    void should_failValidation_when_nameIsEmpty() {
        var request = new CreateNotificationConfigRequest(
                "", ChannelType.WEBHOOK, null,
                null, false, false, false, null
        );

        Set<ConstraintViolation<CreateNotificationConfigRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    @Test
    void should_failValidation_when_nameIsBlank() {
        var request = new CreateNotificationConfigRequest(
                "   ", ChannelType.WEBHOOK, null,
                null, false, false, false, null
        );

        Set<ConstraintViolation<CreateNotificationConfigRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("name");
    }

    // --- Validation: @NotNull channelType ---

    @Test
    void should_failValidation_when_channelTypeIsNull() {
        var request = new CreateNotificationConfigRequest(
                "Valid Name", null, null,
                null, false, false, false, null
        );

        Set<ConstraintViolation<CreateNotificationConfigRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("channelType");
    }

    @Test
    void should_failValidation_when_bothNameAndChannelTypeAreInvalid() {
        var request = new CreateNotificationConfigRequest(
                null, null, null,
                null, false, false, false, null
        );

        Set<ConstraintViolation<CreateNotificationConfigRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(2);
        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("name", "channelType");
    }

    // --- equals / hashCode / toString ---

    @Test
    void should_beEqual_when_allFieldsMatch() {
        var a = new CreateNotificationConfigRequest(
                "Config", ChannelType.WEBHOOK, "https://hook.example.com",
                "wf-1", true, false, true, "template"
        );
        var b = new CreateNotificationConfigRequest(
                "Config", ChannelType.WEBHOOK, "https://hook.example.com",
                "wf-1", true, false, true, "template"
        );

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_nameDiffers() {
        var a = new CreateNotificationConfigRequest(
                "Config A", ChannelType.WEBHOOK, null, null, false, false, false, null
        );
        var b = new CreateNotificationConfigRequest(
                "Config B", ChannelType.WEBHOOK, null, null, false, false, false, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_triggerFlagsDiffer() {
        var a = new CreateNotificationConfigRequest(
                "Config", ChannelType.WEBHOOK, null, null, true, false, false, null
        );
        var b = new CreateNotificationConfigRequest(
                "Config", ChannelType.WEBHOOK, null, null, false, true, false, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_containFieldsInToString() {
        var request = new CreateNotificationConfigRequest(
                "My Alert", ChannelType.WEBHOOK, "https://hook.example.com",
                "wf-42", true, false, true, "Deploy done"
        );

        String str = request.toString();
        assertThat(str).contains("My Alert");
        assertThat(str).contains("WEBHOOK");
        assertThat(str).contains("https://hook.example.com");
        assertThat(str).contains("wf-42");
        assertThat(str).contains("Deploy done");
    }
}
