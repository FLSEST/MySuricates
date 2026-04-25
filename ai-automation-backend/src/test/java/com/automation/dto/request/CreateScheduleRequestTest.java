package com.automation.dto.request;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CreateScheduleRequestTest {

    private static final Instant SCHEDULED_AT = Instant.parse("2024-06-01T10:00:00Z");
    private static final Instant END_DATE = Instant.parse("2024-12-31T23:59:59Z");

    // --- Constructor & accessors ---

    @Test
    void should_returnAllFields_when_constructedWithAllValues() {
        Map<String, Object> inputContext = Map.of("key", "value", "count", 42);

        var request = new CreateScheduleRequest(
                "wf-1",
                "chain-1",
                "WORKFLOW",
                "0 0 * * *",
                "Daily run",
                inputContext,
                "CRON",
                SCHEDULED_AT,
                "PT1H",
                END_DATE,
                10,
                "SKIP"
        );

        assertThat(request.workflowId()).isEqualTo("wf-1");
        assertThat(request.chainId()).isEqualTo("chain-1");
        assertThat(request.targetType()).isEqualTo("WORKFLOW");
        assertThat(request.cronExpression()).isEqualTo("0 0 * * *");
        assertThat(request.description()).isEqualTo("Daily run");
        assertThat(request.inputContext()).isEqualTo(inputContext);
        assertThat(request.scheduleType()).isEqualTo("CRON");
        assertThat(request.scheduledAt()).isEqualTo(SCHEDULED_AT);
        assertThat(request.delayExpression()).isEqualTo("PT1H");
        assertThat(request.endDate()).isEqualTo(END_DATE);
        assertThat(request.maxRuns()).isEqualTo(10);
        assertThat(request.conflictBehavior()).isEqualTo("SKIP");
    }

    @Test
    void should_acceptNullOptionalFields_when_constructedWithNulls() {
        var request = new CreateScheduleRequest(
                null, null, null, null, null, null, null, null, null, null, null, null
        );

        assertThat(request.workflowId()).isNull();
        assertThat(request.chainId()).isNull();
        assertThat(request.targetType()).isNull();
        assertThat(request.cronExpression()).isNull();
        assertThat(request.description()).isNull();
        assertThat(request.inputContext()).isNull();
        assertThat(request.scheduleType()).isNull();
        assertThat(request.scheduledAt()).isNull();
        assertThat(request.delayExpression()).isNull();
        assertThat(request.endDate()).isNull();
        assertThat(request.maxRuns()).isNull();
        assertThat(request.conflictBehavior()).isNull();
    }

    @Test
    void should_returnEmptyInputContext_when_constructedWithEmptyMap() {
        var request = new CreateScheduleRequest(
                "wf-2", null, "WORKFLOW", "* * * * *", null,
                Map.of(), "CRON", null, null, null, null, null
        );

        assertThat(request.inputContext()).isNotNull();
        assertThat(request.inputContext()).isEmpty();
    }

    @Test
    void should_supportChainTarget_when_targetTypeIsChain() {
        var request = new CreateScheduleRequest(
                null, "chain-42", "CHAIN", "0 12 * * *", "Lunch chain",
                null, "CRON", null, null, null, 5, "RUN_ANYWAY"
        );

        assertThat(request.chainId()).isEqualTo("chain-42");
        assertThat(request.targetType()).isEqualTo("CHAIN");
        assertThat(request.workflowId()).isNull();
    }

    @Test
    void should_supportOneTimeSchedule_when_scheduleTypeIsOneTime() {
        var request = new CreateScheduleRequest(
                "wf-3", null, "WORKFLOW", null, "One-time run",
                null, "ONE_TIME", SCHEDULED_AT, null, null, 1, "SKIP"
        );

        assertThat(request.scheduleType()).isEqualTo("ONE_TIME");
        assertThat(request.scheduledAt()).isEqualTo(SCHEDULED_AT);
        assertThat(request.cronExpression()).isNull();
        assertThat(request.maxRuns()).isEqualTo(1);
    }

    // --- Record equality & hashCode ---

    @Test
    void should_beEqual_when_bothHaveSameValues() {
        Map<String, Object> ctx = Map.of("k", "v");
        var a = new CreateScheduleRequest("wf-1", "ch-1", "WORKFLOW", "0 0 * * *", "desc",
                ctx, "CRON", SCHEDULED_AT, "PT1H", END_DATE, 10, "SKIP");
        var b = new CreateScheduleRequest("wf-1", "ch-1", "WORKFLOW", "0 0 * * *", "desc",
                ctx, "CRON", SCHEDULED_AT, "PT1H", END_DATE, 10, "SKIP");

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    void should_beEqual_when_bothHaveAllNulls() {
        var a = new CreateScheduleRequest(null, null, null, null, null, null, null, null, null, null, null, null);
        var b = new CreateScheduleRequest(null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    void should_notBeEqual_when_workflowIdDiffers() {
        var a = new CreateScheduleRequest("wf-1", null, "WORKFLOW", "0 0 * * *", null,
                null, "CRON", null, null, null, null, null);
        var b = new CreateScheduleRequest("wf-2", null, "WORKFLOW", "0 0 * * *", null,
                null, "CRON", null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_cronExpressionDiffers() {
        var a = new CreateScheduleRequest("wf-1", null, "WORKFLOW", "0 0 * * *", null,
                null, "CRON", null, null, null, null, null);
        var b = new CreateScheduleRequest("wf-1", null, "WORKFLOW", "0 12 * * *", null,
                null, "CRON", null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new CreateScheduleRequest("wf-1", null, "WORKFLOW", "0 0 * * *", null,
                null, "CRON", null, null, null, null, null);

        assertThat(request).isNotEqualTo(null);
    }

    @Test
    void should_notBeEqual_when_comparedToDifferentType() {
        var request = new CreateScheduleRequest("wf-1", null, "WORKFLOW", "0 0 * * *", null,
                null, "CRON", null, null, null, null, null);

        assertThat(request).isNotEqualTo("not a request");
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalledWithPopulatedRecord() {
        var request = new CreateScheduleRequest(
                "wf-77", "ch-88", "WORKFLOW", "0 9 * * MON", "Monday morning",
                Map.of("env", "prod"), "CRON", SCHEDULED_AT, "PT30M", END_DATE, 99, "SKIP"
        );

        String s = request.toString();
        assertThat(s).contains("wf-77");
        assertThat(s).contains("ch-88");
        assertThat(s).contains("WORKFLOW");
        assertThat(s).contains("0 9 * * MON");
        assertThat(s).contains("Monday morning");
        assertThat(s).contains("CRON");
        assertThat(s).contains("99");
        assertThat(s).contains("SKIP");
    }
}
