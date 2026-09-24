package com.automation.dto.response;

import com.automation.dto.response.UpcomingScheduleResponse.UpcomingRun;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UpcomingScheduleResponseTest {

    // --- UpcomingScheduleResponse: Constructor & accessors ---

    @Test
    void should_expose_all_fields_when_constructed_with_values() {
        UpcomingRun run = new UpcomingRun("sched-1", "Workflow A", "Project X",
                "chain-1", "Chain A", "WORKFLOW", Instant.parse("2026-04-25T10:00:00Z"));

        UpcomingScheduleResponse response = new UpcomingScheduleResponse(1, List.of(run), null);

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.schedules()).containsExactly(run);
    }

    @Test
    void should_accept_zero_count_and_empty_list() {
        UpcomingScheduleResponse response = new UpcomingScheduleResponse(0, List.of(), null);

        assertThat(response.count()).isZero();
        assertThat(response.schedules()).isEmpty();
    }

    @Test
    void should_accept_null_schedules_list() {
        UpcomingScheduleResponse response = new UpcomingScheduleResponse(0, null, null);

        assertThat(response.count()).isZero();
        assertThat(response.schedules()).isNull();
    }

    @Test
    void should_accept_negative_count_value() {
        UpcomingScheduleResponse response = new UpcomingScheduleResponse(-1, List.of(), null);

        assertThat(response.count()).isEqualTo(-1);
    }

    // --- UpcomingScheduleResponse: Record equality ---

    @Test
    void should_be_equal_when_fields_match() {
        UpcomingRun run = new UpcomingRun("s1", "wf", "proj", "c1", "chain", "WORKFLOW",
                Instant.parse("2026-04-25T10:00:00Z"));
        UpcomingScheduleResponse a = new UpcomingScheduleResponse(1, List.of(run), null);
        UpcomingScheduleResponse b = new UpcomingScheduleResponse(1, List.of(run), null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_not_be_equal_when_count_differs() {
        UpcomingScheduleResponse a = new UpcomingScheduleResponse(1, List.of(), null);
        UpcomingScheduleResponse b = new UpcomingScheduleResponse(2, List.of(), null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_not_be_equal_when_schedules_differ() {
        UpcomingRun run1 = new UpcomingRun("s1", "wf", "proj", "c1", "chain", "WORKFLOW",
                Instant.parse("2026-04-25T10:00:00Z"));
        UpcomingRun run2 = new UpcomingRun("s2", "wf", "proj", "c1", "chain", "WORKFLOW",
                Instant.parse("2026-04-25T10:00:00Z"));
        UpcomingScheduleResponse a = new UpcomingScheduleResponse(1, List.of(run1), null);
        UpcomingScheduleResponse b = new UpcomingScheduleResponse(1, List.of(run2), null);

        assertThat(a).isNotEqualTo(b);
    }

    // --- UpcomingScheduleResponse: toString ---

    @Test
    void should_contain_field_names_in_toString() {
        UpcomingScheduleResponse response = new UpcomingScheduleResponse(2, List.of(), null);

        assertThat(response.toString())
                .contains("count")
                .contains("schedules")
                .contains("2");
    }

    // --- UpcomingRun: Constructor & accessors ---

    @Test
    void upcomingRun_should_expose_all_fields_when_constructed() {
        Instant when = Instant.parse("2026-04-25T12:34:56Z");
        UpcomingRun run = new UpcomingRun("sched-42", "Daily Workflow", "AutoPilot",
                "chain-7", "Release Chain", "CHAIN", when);

        assertThat(run.scheduleId()).isEqualTo("sched-42");
        assertThat(run.workflowName()).isEqualTo("Daily Workflow");
        assertThat(run.projectName()).isEqualTo("AutoPilot");
        assertThat(run.chainId()).isEqualTo("chain-7");
        assertThat(run.chainName()).isEqualTo("Release Chain");
        assertThat(run.targetType()).isEqualTo("CHAIN");
        assertThat(run.scheduledAt()).isEqualTo(when);
    }

    @Test
    void upcomingRun_should_accept_null_values_for_all_fields() {
        UpcomingRun run = new UpcomingRun(null, null, null, null, null, null, null);

        assertThat(run.scheduleId()).isNull();
        assertThat(run.workflowName()).isNull();
        assertThat(run.projectName()).isNull();
        assertThat(run.chainId()).isNull();
        assertThat(run.chainName()).isNull();
        assertThat(run.targetType()).isNull();
        assertThat(run.scheduledAt()).isNull();
    }

    // --- UpcomingRun: Record equality ---

    @Test
    void upcomingRun_should_be_equal_when_all_fields_match() {
        Instant when = Instant.parse("2026-04-25T10:00:00Z");
        UpcomingRun a = new UpcomingRun("s1", "wf", "proj", "c1", "chain", "WORKFLOW", when);
        UpcomingRun b = new UpcomingRun("s1", "wf", "proj", "c1", "chain", "WORKFLOW", when);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void upcomingRun_should_not_be_equal_when_scheduleId_differs() {
        Instant when = Instant.parse("2026-04-25T10:00:00Z");
        UpcomingRun a = new UpcomingRun("s1", "wf", "proj", "c1", "chain", "WORKFLOW", when);
        UpcomingRun b = new UpcomingRun("s2", "wf", "proj", "c1", "chain", "WORKFLOW", when);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void upcomingRun_should_not_be_equal_when_scheduledAt_differs() {
        UpcomingRun a = new UpcomingRun("s1", "wf", "proj", "c1", "chain", "WORKFLOW",
                Instant.parse("2026-04-25T10:00:00Z"));
        UpcomingRun b = new UpcomingRun("s1", "wf", "proj", "c1", "chain", "WORKFLOW",
                Instant.parse("2026-04-25T11:00:00Z"));

        assertThat(a).isNotEqualTo(b);
    }

    // --- UpcomingRun: toString ---

    @Test
    void upcomingRun_should_contain_field_names_in_toString() {
        UpcomingRun run = new UpcomingRun("sched-1", "wf", "proj", "c1", "chain", "WORKFLOW",
                Instant.parse("2026-04-25T10:00:00Z"));

        assertThat(run.toString())
                .contains("scheduleId")
                .contains("workflowName")
                .contains("projectName")
                .contains("chainId")
                .contains("chainName")
                .contains("targetType")
                .contains("scheduledAt");
    }

    @Test
    void should_carry_the_end_of_an_active_skip_until_pause() {
        Instant pausedUntil = Instant.parse("2026-10-01T22:00:00Z");

        UpcomingScheduleResponse response = new UpcomingScheduleResponse(0, List.of(), pausedUntil);

        assertThat(response.pausedUntil()).isEqualTo(pausedUntil);
        assertThat(response).isNotEqualTo(new UpcomingScheduleResponse(0, List.of(), null));
    }
}
