package com.automation.dto.response;

import com.automation.entity.RunStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledWorkflowResponseTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:30:00Z");
    private static final Instant LATER = Instant.parse("2026-01-15T12:00:00Z");
    private static final Instant END = Instant.parse("2026-06-01T00:00:00Z");

    // ============================================================
    // Constructor & accessors
    // ============================================================

    @Test
    void should_setAllFields_when_constructed() {
        var ctx = Map.<String, Object>of("env", "prod", "region", "eu");
        var response = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "Deploy Workflow", "Ops Project",
                "chain-1", "Deploy Chain", "WORKFLOW",
                "0 0 * * *", "CRON", NOW,
                "PT1H", true, "Daily deploy",
                ctx, LATER, END, NOW,
                RunStatus.COMPLETED, END, 10, 3, "SKIP", false, null);

        assertThat(response.id()).isEqualTo("sched-1");
        assertThat(response.workflowId()).isEqualTo("wf-1");
        assertThat(response.workflowName()).isEqualTo("Deploy Workflow");
        assertThat(response.projectName()).isEqualTo("Ops Project");
        assertThat(response.chainId()).isEqualTo("chain-1");
        assertThat(response.chainName()).isEqualTo("Deploy Chain");
        assertThat(response.targetType()).isEqualTo("WORKFLOW");
        assertThat(response.cronExpression()).isEqualTo("0 0 * * *");
        assertThat(response.scheduleType()).isEqualTo("CRON");
        assertThat(response.scheduledAt()).isEqualTo(NOW);
        assertThat(response.delayExpression()).isEqualTo("PT1H");
        assertThat(response.enabled()).isTrue();
        assertThat(response.description()).isEqualTo("Daily deploy");
        assertThat(response.inputContext()).containsEntry("env", "prod").containsEntry("region", "eu");
        assertThat(response.lastRunAt()).isEqualTo(LATER);
        assertThat(response.nextRunAt()).isEqualTo(END);
        assertThat(response.createdAt()).isEqualTo(NOW);
        assertThat(response.lastRunStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(response.endDate()).isEqualTo(END);
        assertThat(response.maxRuns()).isEqualTo(10);
        assertThat(response.runCount()).isEqualTo(3);
        assertThat(response.conflictBehavior()).isEqualTo("SKIP");
    }

    @Test
    void should_returnFalseEnabled_when_constructedWithFalse() {
        var response = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "WF", "Proj",
                null, null, "WORKFLOW",
                "0 0 * * *", "CRON", NOW,
                null, false, null,
                null, null, null, NOW,
                null, null, 0, 0, null, false, null);

        assertThat(response.enabled()).isFalse();
    }

    @Test
    void should_acceptAllNulls_when_constructed() {
        var response = new ScheduledWorkflowResponse(
                null, null, null, null,
                null, null, null,
                null, null, null,
                null, false, null,
                null, null, null, null,
                null, null, 0, 0, null, false, null);

        assertThat(response.id()).isNull();
        assertThat(response.workflowId()).isNull();
        assertThat(response.workflowName()).isNull();
        assertThat(response.projectName()).isNull();
        assertThat(response.chainId()).isNull();
        assertThat(response.chainName()).isNull();
        assertThat(response.targetType()).isNull();
        assertThat(response.cronExpression()).isNull();
        assertThat(response.scheduleType()).isNull();
        assertThat(response.scheduledAt()).isNull();
        assertThat(response.delayExpression()).isNull();
        assertThat(response.enabled()).isFalse();
        assertThat(response.description()).isNull();
        assertThat(response.inputContext()).isNull();
        assertThat(response.lastRunAt()).isNull();
        assertThat(response.nextRunAt()).isNull();
        assertThat(response.createdAt()).isNull();
        assertThat(response.lastRunStatus()).isNull();
        assertThat(response.endDate()).isNull();
        assertThat(response.maxRuns()).isZero();
        assertThat(response.runCount()).isZero();
        assertThat(response.conflictBehavior()).isNull();
    }

    @Test
    void should_acceptEmptyInputContext_when_constructed() {
        var response = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "WF", "Proj",
                null, null, "WORKFLOW",
                "0 0 * * *", "CRON", NOW,
                null, true, "desc",
                Map.of(), null, null, NOW,
                null, null, 0, 0, null, false, null);

        assertThat(response.inputContext()).isEmpty();
    }

    @Test
    void should_preserveRunStatus_when_constructedWithDifferentStatuses() {
        var response = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "WF", "Proj",
                null, null, "WORKFLOW",
                null, null, null,
                null, true, null,
                null, null, null, null,
                RunStatus.RUNNING, null, 0, 0, null, false, null);

        assertThat(response.lastRunStatus()).isEqualTo(RunStatus.RUNNING);
    }

    // ============================================================
    // Equality & hashCode
    // ============================================================

    @Test
    void should_beEqual_when_sameFieldValues() {
        var ctx = Map.<String, Object>of("key", "val");
        var a = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "WF", "Proj",
                "chain-1", "Chain", "WORKFLOW",
                "0 0 * * *", "CRON", NOW,
                "PT1H", true, "desc",
                ctx, LATER, END, NOW,
                RunStatus.COMPLETED, END, 10, 3, "SKIP", false, null);
        var b = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "WF", "Proj",
                "chain-1", "Chain", "WORKFLOW",
                "0 0 * * *", "CRON", NOW,
                "PT1H", true, "desc",
                ctx, LATER, END, NOW,
                RunStatus.COMPLETED, END, 10, 3, "SKIP", false, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        var a = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "WF", "Proj",
                null, null, null, null, null, null,
                null, false, null, null, null, null, null,
                null, null, 0, 0, null, false, null);
        var b = new ScheduledWorkflowResponse(
                "sched-2", "wf-1", "WF", "Proj",
                null, null, null, null, null, null,
                null, false, null, null, null, null, null,
                null, null, 0, 0, null, false, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentWorkflowId() {
        var a = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "WF", "Proj",
                null, null, null, null, null, null,
                null, false, null, null, null, null, null,
                null, null, 0, 0, null, false, null);
        var b = new ScheduledWorkflowResponse(
                "sched-1", "wf-2", "WF", "Proj",
                null, null, null, null, null, null,
                null, false, null, null, null, null, null,
                null, null, 0, 0, null, false, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentEnabled() {
        var a = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "WF", "Proj",
                null, null, null, null, null, null,
                null, true, null, null, null, null, null,
                null, null, 0, 0, null, false, null);
        var b = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "WF", "Proj",
                null, null, null, null, null, null,
                null, false, null, null, null, null, null,
                null, null, 0, 0, null, false, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentMaxRuns() {
        var a = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "WF", "Proj",
                null, null, null, null, null, null,
                null, false, null, null, null, null, null,
                null, null, 10, 0, null, false, null);
        var b = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "WF", "Proj",
                null, null, null, null, null, null,
                null, false, null, null, null, null, null,
                null, null, 20, 0, null, false, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentRunCount() {
        var a = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "WF", "Proj",
                null, null, null, null, null, null,
                null, false, null, null, null, null, null,
                null, null, 0, 1, null, false, null);
        var b = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "WF", "Proj",
                null, null, null, null, null, null,
                null, false, null, null, null, null, null,
                null, null, 0, 2, null, false, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentLastRunStatus() {
        var a = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "WF", "Proj",
                null, null, null, null, null, null,
                null, false, null, null, null, null, null,
                RunStatus.COMPLETED, null, 0, 0, null, false, null);
        var b = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "WF", "Proj",
                null, null, null, null, null, null,
                null, false, null, null, null, null, null,
                RunStatus.RUNNING, null, 0, 0, null, false, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_bothAllNulls() {
        var a = new ScheduledWorkflowResponse(
                null, null, null, null,
                null, null, null, null, null, null,
                null, false, null, null, null, null, null,
                null, null, 0, 0, null, false, null);
        var b = new ScheduledWorkflowResponse(
                null, null, null, null,
                null, null, null, null, null, null,
                null, false, null, null, null, null, null,
                null, null, 0, 0, null, false, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // ============================================================
    // toString
    // ============================================================

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new ScheduledWorkflowResponse(
                "sched-42", "wf-99", "Nightly Build", "CI Project",
                "chain-5", "Build Chain", "WORKFLOW",
                "0 0 3 * *", "CRON", NOW,
                "PT2H", true, "Nightly build schedule",
                Map.of("branch", "main"), LATER, END, NOW,
                RunStatus.COMPLETED, END, 100, 42, "QUEUE", false, null);
        var str = response.toString();

        assertThat(str).contains("sched-42");
        assertThat(str).contains("wf-99");
        assertThat(str).contains("Nightly Build");
        assertThat(str).contains("CI Project");
        assertThat(str).contains("0 0 3 * *");
        assertThat(str).contains("CRON");
        assertThat(str).contains("Nightly build schedule");
        assertThat(str).contains("COMPLETED");
    }

    @Test
    void should_handleNullsInToString_when_fieldsAreNull() {
        var response = new ScheduledWorkflowResponse(
                null, null, null, null,
                null, null, null, null, null, null,
                null, false, null, null, null, null, null,
                null, null, 0, 0, null, false, null);
        var str = response.toString();

        assertThat(str).contains("null");
    }

    @Test
    void should_carryPausedUntil_when_constructed() {
        var response = new ScheduledWorkflowResponse(
                "sched-1", "wf-1", "Deploy Workflow", null, null, null, "WORKFLOW",
                "0 0 8 * * *", "CRON", null, null, true, null, Map.of(), null, LATER, NOW,
                null, null, 0, 0, "SKIP", false, END);

        assertThat(response.pausedUntil()).isEqualTo(END);
        assertThat(response.nextRunAt()).isEqualTo(LATER);
    }
}
