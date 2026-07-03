package com.automation.dto.request;

import com.automation.entity.ActivityLog.LogLevel;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateActivityLogRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private CreateActivityLogRequest fullRequest() {
        return new CreateActivityLogRequest(
                LogLevel.INFO,
                "Workflow started",
                "workflow-executor",
                "ref-123",
                "wf-456",
                Map.of("key", "value"));
    }

    // --- Record accessors ---

    @Test
    void should_returnLevel_when_constructed() {
        assertThat(fullRequest().level()).isEqualTo(LogLevel.INFO);
    }

    @Test
    void should_returnMessage_when_constructed() {
        assertThat(fullRequest().message()).isEqualTo("Workflow started");
    }

    @Test
    void should_returnSource_when_constructed() {
        assertThat(fullRequest().source()).isEqualTo("workflow-executor");
    }

    @Test
    void should_returnReferenceId_when_constructed() {
        assertThat(fullRequest().referenceId()).isEqualTo("ref-123");
    }

    @Test
    void should_returnWorkflowId_when_constructed() {
        assertThat(fullRequest().workflowId()).isEqualTo("wf-456");
    }

    @Test
    void should_returnMetadata_when_constructed() {
        assertThat(fullRequest().metadata()).containsEntry("key", "value");
    }

    // --- Null safety ---

    @Test
    void should_returnNulls_when_constructedWithAllNulls() {
        var request = new CreateActivityLogRequest(null, null, null, null, null, null);

        assertThat(request.level()).isNull();
        assertThat(request.message()).isNull();
        assertThat(request.source()).isNull();
        assertThat(request.referenceId()).isNull();
        assertThat(request.workflowId()).isNull();
        assertThat(request.metadata()).isNull();
    }

    // --- Bean validation (record declares no constraints) ---

    @Test
    void should_passValidation_when_fullyPopulated() {
        Set<ConstraintViolation<CreateActivityLogRequest>> violations = validator.validate(fullRequest());

        assertThat(violations).isEmpty();
    }

    @Test
    void should_passValidation_when_allFieldsNull_because_noConstraintsDeclared() {
        var request = new CreateActivityLogRequest(null, null, null, null, null, null);

        Set<ConstraintViolation<CreateActivityLogRequest>> violations = validator.validate(request);

        assertThat(violations).isEmpty();
    }

    // --- All log levels ---

    @Test
    void should_acceptAllLogLevels() {
        for (LogLevel level : LogLevel.values()) {
            var request = new CreateActivityLogRequest(level, "msg", "src", null, null, null);

            assertThat(request.level()).as("level=%s", level).isEqualTo(level);
        }
    }

    // --- equals / hashCode ---

    @Test
    void should_beEqual_when_allComponentsMatch() {
        var a = fullRequest();
        var b = fullRequest();

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_levelDiffers() {
        var a = new CreateActivityLogRequest(LogLevel.INFO, "msg", "src", null, null, null);
        var b = new CreateActivityLogRequest(LogLevel.ERROR, "msg", "src", null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_messageDiffers() {
        var a = new CreateActivityLogRequest(LogLevel.INFO, "msg-a", "src", null, null, null);
        var b = new CreateActivityLogRequest(LogLevel.INFO, "msg-b", "src", null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        assertThat(fullRequest()).isNotEqualTo(null);
    }

    @Test
    void should_notBeEqual_when_comparedToDifferentType() {
        assertThat(fullRequest()).isNotEqualTo("not a request");
    }

    // --- toString ---

    @Test
    void should_containAllComponentValues_inToString() {
        var text = fullRequest().toString();

        assertThat(text).contains("INFO");
        assertThat(text).contains("Workflow started");
        assertThat(text).contains("workflow-executor");
        assertThat(text).contains("ref-123");
        assertThat(text).contains("wf-456");
    }
}
