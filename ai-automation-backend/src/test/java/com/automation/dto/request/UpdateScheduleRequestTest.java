package com.automation.dto.request;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateScheduleRequestTest {

    private static final Instant NOW = Instant.parse("2026-04-08T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-12-31T23:59:59Z");

    @Test
    void should_returnCronExpression_when_constructedWithCron() {
        var request = new UpdateScheduleRequest("0 0 * * *", null, null, null, null, null, null);

        assertThat(request.cronExpression()).isEqualTo("0 0 * * *");
    }

    @Test
    void should_returnDescription_when_constructedWithDescription() {
        var request = new UpdateScheduleRequest(null, "Daily backup", null, null, null, null, null);

        assertThat(request.description()).isEqualTo("Daily backup");
    }

    @Test
    void should_returnInputContext_when_constructedWithMap() {
        var context = Map.<String, Object>of("key", "value", "count", 42);
        var request = new UpdateScheduleRequest(null, null, context, null, null, null, null);

        assertThat(request.inputContext()).hasSize(2);
        assertThat(request.inputContext()).containsEntry("key", "value");
        assertThat(request.inputContext()).containsEntry("count", 42);
    }

    @Test
    void should_returnScheduledAt_when_constructedWithInstant() {
        var request = new UpdateScheduleRequest(null, null, null, NOW, null, null, null);

        assertThat(request.scheduledAt()).isEqualTo(NOW);
    }

    @Test
    void should_returnEndDate_when_constructedWithInstant() {
        var request = new UpdateScheduleRequest(null, null, null, null, LATER, null, null);

        assertThat(request.endDate()).isEqualTo(LATER);
    }

    @Test
    void should_returnMaxRuns_when_constructedWithInteger() {
        var request = new UpdateScheduleRequest(null, null, null, null, null, 10, null);

        assertThat(request.maxRuns()).isEqualTo(10);
    }

    @Test
    void should_returnNullForAllFields_when_constructedWithAllNulls() {
        var request = new UpdateScheduleRequest(null, null, null, null, null, null, null);

        assertThat(request.cronExpression()).isNull();
        assertThat(request.description()).isNull();
        assertThat(request.inputContext()).isNull();
        assertThat(request.scheduledAt()).isNull();
        assertThat(request.endDate()).isNull();
        assertThat(request.maxRuns()).isNull();
    }

    @Test
    void should_returnAllFields_when_fullyPopulated() {
        var context = Map.<String, Object>of("env", "prod");
        var request = new UpdateScheduleRequest("0 0 * * *", "Daily", context, NOW, LATER, 5, null);

        assertThat(request.cronExpression()).isEqualTo("0 0 * * *");
        assertThat(request.description()).isEqualTo("Daily");
        assertThat(request.inputContext()).containsExactlyEntriesOf(context);
        assertThat(request.scheduledAt()).isEqualTo(NOW);
        assertThat(request.endDate()).isEqualTo(LATER);
        assertThat(request.maxRuns()).isEqualTo(5);
    }

    @Test
    void should_handleComplexInputContext_when_nestedMapsProvided() {
        var nested = Map.<String, Object>of(
                "database", Map.of("host", "localhost", "port", 5432),
                "features", List.of("alpha", "beta"),
                "enabled", true
        );
        var request = new UpdateScheduleRequest(null, null, nested, null, null, null, null);

        assertThat(request.inputContext()).hasSize(3);
        assertThat(request.inputContext().get("database")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        var db = (Map<String, Object>) request.inputContext().get("database");
        assertThat(db).containsEntry("host", "localhost").containsEntry("port", 5432);
        assertThat(request.inputContext().get("features")).isInstanceOf(List.class);
        assertThat(request.inputContext().get("enabled")).isEqualTo(true);
    }

    @Test
    void should_returnEmptyMap_when_inputContextIsEmpty() {
        var request = new UpdateScheduleRequest(null, null, Map.of(), null, null, null, null);

        assertThat(request.inputContext()).isEmpty();
    }

    @Test
    void should_handleZeroMaxRuns() {
        var request = new UpdateScheduleRequest(null, null, null, null, null, 0, null);

        assertThat(request.maxRuns()).isZero();
    }

    @Test
    void should_handleEpochInstant_when_scheduledAtIsEpoch() {
        var request = new UpdateScheduleRequest(null, null, null, Instant.EPOCH, null, null, null);

        assertThat(request.scheduledAt()).isEqualTo(Instant.EPOCH);
    }

    // --- equals / hashCode ---

    @Test
    void should_beEqual_when_allFieldsMatch() {
        var context = Map.<String, Object>of("key", "value");
        var a = new UpdateScheduleRequest("0 0 * * *", "Daily", context, NOW, LATER, 5, null);
        var b = new UpdateScheduleRequest("0 0 * * *", "Daily", context, NOW, LATER, 5, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveNullFields() {
        var a = new UpdateScheduleRequest(null, null, null, null, null, null, null);
        var b = new UpdateScheduleRequest(null, null, null, null, null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveEmptyInputContext() {
        var a = new UpdateScheduleRequest(null, null, Map.of(), null, null, null, null);
        var b = new UpdateScheduleRequest(null, null, Collections.emptyMap(), null, null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_cronExpressionDiffers() {
        var a = new UpdateScheduleRequest("0 0 * * *", "Desc", null, null, null, null, null);
        var b = new UpdateScheduleRequest("0 12 * * *", "Desc", null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_descriptionDiffers() {
        var a = new UpdateScheduleRequest("0 0 * * *", "Daily", null, null, null, null, null);
        var b = new UpdateScheduleRequest("0 0 * * *", "Weekly", null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_inputContextDiffers() {
        var a = new UpdateScheduleRequest(null, null, Map.of("a", 1), null, null, null, null);
        var b = new UpdateScheduleRequest(null, null, Map.of("b", 2), null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_scheduledAtDiffers() {
        var a = new UpdateScheduleRequest(null, null, null, NOW, null, null, null);
        var b = new UpdateScheduleRequest(null, null, null, LATER, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_endDateDiffers() {
        var a = new UpdateScheduleRequest(null, null, null, null, NOW, null, null);
        var b = new UpdateScheduleRequest(null, null, null, null, LATER, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_maxRunsDiffers() {
        var a = new UpdateScheduleRequest(null, null, null, null, null, 5, null);
        var b = new UpdateScheduleRequest(null, null, null, null, null, 10, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new UpdateScheduleRequest("0 0 * * *", "Daily", Map.of(), NOW, LATER, 5, null);

        assertThat(request).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldNamesInToString() {
        var request = new UpdateScheduleRequest("0 0 * * *", "Daily", Map.of(), NOW, LATER, 5, null);

        assertThat(request.toString()).contains("cronExpression");
        assertThat(request.toString()).contains("description");
        assertThat(request.toString()).contains("inputContext");
        assertThat(request.toString()).contains("scheduledAt");
        assertThat(request.toString()).contains("endDate");
        assertThat(request.toString()).contains("maxRuns");
    }

    @Test
    void should_containValuesInToString() {
        var request = new UpdateScheduleRequest("0 0 * * *", "Daily", Map.of(), NOW, LATER, 5, null);

        assertThat(request.toString()).contains("0 0 * * *");
        assertThat(request.toString()).contains("Daily");
    }
}
