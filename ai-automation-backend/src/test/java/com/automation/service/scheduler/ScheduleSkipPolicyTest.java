package com.automation.service.scheduler;

import com.automation.config.SchedulerConfig;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.Workflow;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Locks the skip-until parsing and skip mechanics before extraction (Audit-TODO 05dbd0e9). */
@ExtendWith(MockitoExtension.class)
class ScheduleSkipPolicyTest {

    @Mock private SchedulerConfig schedulerConfig;
    @Mock private ScheduledWorkflowRepository scheduledRepository;
    @Mock private ActivityLogService activityLogService;

    private ScheduleSkipPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ScheduleSkipPolicy(schedulerConfig, scheduledRepository, activityLogService);
    }

    private ScheduledWorkflow cron(String expr, Instant nextRunAt) {
        return ScheduledWorkflow.builder().id("s-1").workflow(Workflow.builder().id("wf").name("Daily").build())
                .cronExpression(expr).scheduleType(ScheduleType.CRON).enabled(true).nextRunAt(nextRunAt).build();
    }

    // --- parseSkipUntil ---

    @Test
    void parseSkipUntil_blank_isEmpty() {
        when(schedulerConfig.getSkipUntil()).thenReturn("  ");

        assertThat(policy.parseSkipUntil()).isEmpty();
        verify(activityLogService, never()).warn(any(), anyString(), anyString());
    }

    @Test
    void parseSkipUntil_validValue_isTrimmedAndParsed() {
        when(schedulerConfig.getSkipUntil()).thenReturn(" 2026-05-01T00:00:00Z ");

        assertThat(policy.parseSkipUntil()).contains(Instant.parse("2026-05-01T00:00:00Z"));
    }

    @Test
    void parseSkipUntil_invalid_failsOpen_andWarnsOncePerDistinctValue() {
        when(schedulerConfig.getSkipUntil()).thenReturn("next tuesday");

        assertThat(policy.parseSkipUntil()).isEmpty();
        assertThat(policy.parseSkipUntil()).isEmpty();
        verify(activityLogService, times(1)).warn(eq(LogCategory.SCHEDULE),
                eq("Invalid scheduler skip-until value (ignored): next tuesday"), eq("SchedulerService"));

        when(schedulerConfig.getSkipUntil()).thenReturn("soon");
        policy.parseSkipUntil();
        verify(activityLogService).warn(eq(LogCategory.SCHEDULE),
                eq("Invalid scheduler skip-until value (ignored): soon"), eq("SchedulerService"));
    }

    @Test
    void parseSkipUntil_warnsAgainForSameBadValue_afterValidOrBlankInBetween() {
        when(schedulerConfig.getSkipUntil()).thenReturn("bad", "2026-05-01T00:00:00Z", "bad");

        policy.parseSkipUntil();
        policy.parseSkipUntil();
        policy.parseSkipUntil();

        verify(activityLogService, times(2)).warn(eq(LogCategory.SCHEDULE),
                eq("Invalid scheduler skip-until value (ignored): bad"), eq("SchedulerService"));
    }

    @Test
    void parseSkipUntil_dedupesOnRawValue_notTrimmed() {
        when(schedulerConfig.getSkipUntil()).thenReturn("bad", " bad");

        policy.parseSkipUntil();
        policy.parseSkipUntil();

        verify(activityLogService, times(2)).warn(eq(LogCategory.SCHEDULE), anyString(), eq("SchedulerService"));
    }

    // --- skipScheduleUntil / skipScheduleForResume ---

    @Test
    void skipScheduleUntil_cron_movesToFirstOccurrenceNotBeforeUntil_inclusive() {
        Instant until = LocalDateTime.of(2026, 4, 27, 8, 0).atZone(ZoneId.systemDefault()).toInstant();
        ScheduledWorkflow sw = cron("0 0 8 * * *", until.minusSeconds(86400));

        policy.skipScheduleUntil(sw, until);

        assertThat(sw.getNextRunAt()).isEqualTo(until);
        assertThat(sw.isEnabled()).isTrue();
        verify(scheduledRepository).save(sw);
    }

    @Test
    void skipScheduleUntil_once_isDisabledAndCleared() {
        ScheduledWorkflow sw = ScheduledWorkflow.builder().id("s-2").workflow(Workflow.builder().id("wf").name("One").build())
                .cronExpression("").scheduleType(ScheduleType.ONCE).enabled(true)
                .scheduledAt(Instant.parse("2026-04-26T10:00:00Z")).nextRunAt(Instant.parse("2026-04-26T10:00:00Z")).build();

        policy.skipScheduleUntil(sw, Instant.parse("2026-04-27T00:00:00Z"));

        assertThat(sw.isEnabled()).isFalse();
        assertThat(sw.getNextRunAt()).isNull();
        verify(scheduledRepository).save(sw);
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipScheduleUntil_logsExactMetadataKeys() {
        Instant until = LocalDateTime.of(2026, 4, 27, 8, 0).atZone(ZoneId.systemDefault()).toInstant();
        Instant previous = until.minusSeconds(86400);
        ScheduledWorkflow sw = cron("0 0 8 * * *", previous);

        policy.skipScheduleUntil(sw, until);

        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(activityLogService).log(eq(LogLevel.INFO), eq(LogCategory.SCHEDULE),
                eq("Schedule skipped: Daily"), eq("SchedulerService"), eq("s-1"), meta.capture());
        assertThat(meta.getValue()).containsOnlyKeys("reason", "previousNextRunAt", "nextRunAt", "skipUntil");
        assertThat(meta.getValue()).containsEntry("reason", "scheduler-skip-until")
                .containsEntry("previousNextRunAt", previous.toString())
                .containsEntry("nextRunAt", until.toString())
                .containsEntry("skipUntil", until.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipScheduleUntil_nullPreviousAndNoNext_omitsThoseKeys() {
        ScheduledWorkflow sw = ScheduledWorkflow.builder().id("s-3").workflow(Workflow.builder().id("wf").name("One").build())
                .cronExpression("").scheduleType(ScheduleType.ONCE).enabled(true).nextRunAt(null).build();

        policy.skipScheduleUntil(sw, Instant.parse("2026-04-27T00:00:00Z"));

        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(activityLogService).log(any(), any(), anyString(), anyString(), eq("s-3"), meta.capture());
        assertThat(meta.getValue()).containsOnlyKeys("reason", "skipUntil");
    }

    @Test
    @SuppressWarnings("unchecked")
    void skipScheduleForResume_usesResumeReasonAndGapMinutes() {
        Instant now = LocalDateTime.of(2026, 4, 27, 9, 30).atZone(ZoneId.systemDefault()).toInstant();
        ScheduledWorkflow sw = cron("0 0 8 * * *", now.minusSeconds(3600 * 25));

        policy.skipScheduleForResume(sw, now, 4320L);

        assertThat(sw.getNextRunAt()).isEqualTo(LocalDateTime.of(2026, 4, 28, 8, 0).atZone(ZoneId.systemDefault()).toInstant());
        ArgumentCaptor<Map<String, Object>> meta = ArgumentCaptor.forClass(Map.class);
        verify(activityLogService).log(eq(LogLevel.INFO), eq(LogCategory.SCHEDULE),
                eq("Schedule skipped (system suspend/resume): Daily"), eq("SchedulerService"), eq("s-1"), meta.capture());
        assertThat(meta.getValue()).containsEntry("reason", "suspend-resume").containsEntry("suspendGapMinutes", 4320L);
        assertThat(meta.getValue()).doesNotContainKey("skipUntil");
    }

    @Test
    void parseSkipUntil_afterValidValue_returnsPresent_andResetsWarnState() {
        when(schedulerConfig.getSkipUntil()).thenReturn("2026-05-01T00:00:00Z");

        Optional<Instant> parsed = policy.parseSkipUntil();

        assertThat(parsed).isPresent();
        verify(activityLogService, never()).warn(any(), anyString(), anyString());
    }

    // --- read views: the run the scheduler will actually start (TODO a776fd2a) ---

    private static Instant local(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 4, day, hour, minute).atZone(ZoneId.systemDefault()).toInstant();
    }

    private ScheduledWorkflow once(Instant at) {
        return ScheduledWorkflow.builder().id("s-once").workflow(Workflow.builder().id("wf").name("One").build())
                .cronExpression("").scheduleType(ScheduleType.ONCE).enabled(true).scheduledAt(at).nextRunAt(at).build();
    }

    @Test
    void activeSkipUntil_onlyWhileAhead_andQuietOnABadValue() {
        Instant until = Instant.parse("2026-05-01T00:00:00Z");
        when(schedulerConfig.getSkipUntil()).thenReturn(" " + until + " ");

        assertThat(policy.activeSkipUntil(until.minusSeconds(1))).contains(until);
        assertThat(policy.activeSkipUntil(until)).isEmpty();

        when(schedulerConfig.getSkipUntil()).thenReturn("next tuesday");
        assertThat(policy.activeSkipUntil(Instant.parse("2026-04-01T00:00:00Z"))).isEmpty();
        // Views call this on every GET: no activity-log rows, the tick warns about bad values.
        verifyNoInteractions(activityLogService);
    }

    @Test
    void nextRunAfter_cronLandsAtOrAfterTheEnd_oneOffNowhere() {
        ScheduledWorkflow daily = cron("0 0 8 * * *", local(26, 8, 0));

        assertThat(policy.nextRunAfter(daily, local(28, 8, 0))).isEqualTo(local(28, 8, 0));
        assertThat(policy.nextRunAfter(daily, local(28, 8, 1))).isEqualTo(local(29, 8, 0));
        assertThat(policy.nextRunAfter(once(local(27, 9, 0)), local(28, 8, 0))).isNull();
    }

    @Test
    void effectiveNextRun_movesAStoredRunInsideThePauseBehindIt() {
        Optional<Instant> pause = Optional.of(local(28, 12, 0));
        ScheduledWorkflow held = cron("0 0 8 * * *", local(27, 8, 0));
        ScheduledWorkflow after = cron("0 0 8 * * *", local(29, 8, 0));

        assertThat(policy.heldBySkipUntil(held, pause)).isTrue();
        assertThat(policy.effectiveNextRun(held, pause)).isEqualTo(local(29, 8, 0));
        assertThat(policy.heldBySkipUntil(after, pause)).isFalse();
        assertThat(policy.effectiveNextRun(after, pause)).isEqualTo(local(29, 8, 0));
        assertThat(policy.effectiveNextRun(held, Optional.empty())).isEqualTo(local(27, 8, 0));
        assertThat(held.getNextRunAt()).as("a view never writes").isEqualTo(local(27, 8, 0));
    }

    @Test
    void effectiveNextRun_oneOffInsideThePauseExpires_disabledHasNone() {
        Optional<Instant> pause = Optional.of(local(28, 0, 0));
        ScheduledWorkflow disabled = cron("0 0 8 * * *", null);

        assertThat(policy.effectiveNextRun(once(local(27, 9, 0)), pause)).isNull();
        assertThat(policy.heldBySkipUntil(disabled, pause)).isFalse();
        assertThat(policy.effectiveNextRun(disabled, pause)).isNull();
    }

    @Test
    void effectiveNextRun_unparseableCron_keepsTheStoredValue() {
        ScheduledWorkflow broken = cron("not a cron", local(27, 8, 0));

        assertThat(policy.effectiveNextRun(broken, Optional.of(local(28, 0, 0)))).isEqualTo(local(27, 8, 0));
    }

    @Test
    void effectiveNextRun_predictsWhereTheTickPutsTheSchedule() {
        Instant until = local(28, 12, 0);
        ScheduledWorkflow sw = cron("0 30 * * * *", local(27, 10, 30));
        Instant predicted = policy.effectiveNextRun(sw, Optional.of(until));

        policy.skipScheduleUntil(sw, until);

        assertThat(sw.getNextRunAt()).isEqualTo(predicted).isEqualTo(local(28, 12, 30));
    }
}
