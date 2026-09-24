package com.automation.service.scheduler;

import com.automation.dto.response.UpcomingScheduleResponse;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.repository.ScheduledWorkflowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Projects enabled schedules onto the calendar: which runs are still due today / this week,
 * which schedules have their next run in a period, and the next occurrences of a cron expression.
 *
 * <p>All of them honour an active skip-until pause ({@code automation.scheduler.skip-until}):
 * occurrences inside it are skipped by the tick, so they are no upcoming runs (TODO a776fd2a). The
 * dates of schedules and the dashboard also honour the maintenance switch, under which nothing
 * starts: no run is upcoming while it is on (TODO 7704b1e9). Both come from one
 * {@link SchedulerPause} per call.
 *
 * <p>Extracted from {@code SchedulerService} (Audit-TODO 05dbd0e9). The read-only transactions
 * on the two "remaining" methods are load-bearing: {@link #getUpcomingRuns} navigates the lazy
 * {@code workflow.project} / {@code chain} associations and OSIV is off.
 */
@Service
@RequiredArgsConstructor
public class UpcomingRunsCalculator {

    private final ScheduledWorkflowRepository scheduledRepository;
    private final Clock clock;
    private final ScheduleSkipPolicy skipPolicy;

    /** End of the active skip-until pause, empty when none is configured or it already passed. */
    public Optional<Instant> pausedUntil() {
        return skipPolicy.activeSkipUntil(Instant.now(clock));
    }

    /** Both pauses now — the maintenance switch and an active skip-until window. */
    public SchedulerPause pause() {
        return skipPolicy.activePause(Instant.now(clock));
    }

    /**
     * The next run the scheduler will actually start: none under the maintenance switch, else the
     * run behind an active skip-until pause.
     */
    public Instant nextRun(ScheduledWorkflow scheduled) {
        return nextRun(scheduled, pause());
    }

    /** {@link #nextRun(ScheduledWorkflow)} with a pause read once for many schedules. */
    public Instant nextRun(ScheduledWorkflow scheduled, SchedulerPause pause) {
        return skipPolicy.effectiveNextRun(scheduled, pause);
    }

    /**
     * Enabled schedules whose next run — as the scheduler will actually start it — lies in
     * [from, to]: the dates of the calendar. With an active skip-until pause a stored next run inside
     * the pause moves behind it and a one-off drops out, so the lookup widens to every schedule
     * stored up to {@code to} and filters on the run that really comes; under the maintenance switch
     * none comes.
     */
    @Transactional(readOnly = true)
    public List<ScheduledWorkflow> findWithNextRunBetween(Instant from, Instant to) {
        SchedulerPause pause = pause();
        if (!pause.active()) {
            return scheduledRepository.findSchedulesInRange(from, to);
        }
        return scheduledRepository.findSchedulesInRange(Instant.EPOCH, to).stream()
                .filter(s -> {
                    Instant next = skipPolicy.effectiveNextRun(s, pause);
                    return next != null && !next.isBefore(from) && !next.isAfter(to);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public UpcomingScheduleResponse getRemainingToday() {
        Instant endOfDay = LocalDate.now(clock).plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        return getUpcomingRuns(endOfDay);
    }

    @Transactional(readOnly = true)
    public UpcomingScheduleResponse getRemainingThisWeek() {
        LocalDate nextMonday = LocalDate.now(clock).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        Instant endOfWeek = nextMonday.atStartOfDay(ZoneId.systemDefault()).toInstant();
        return getUpcomingRuns(endOfWeek);
    }

    private UpcomingScheduleResponse getUpcomingRuns(Instant until) {
        Instant now = Instant.now(clock);
        SchedulerPause pause = skipPolicy.activePause(now);
        Optional<Instant> skipUntil = pause.skipUntil();
        if (pause.maintenance()) {
            // Nothing starts while the switch is on, and it has no end: no run is upcoming.
            return new UpcomingScheduleResponse(0, List.of(), skipUntil.orElse(null), true);
        }
        List<ScheduledWorkflow> enabled = scheduledRepository.findByEnabledTrue();
        List<UpcomingScheduleResponse.UpcomingRun> upcoming = new ArrayList<>();

        for (ScheduledWorkflow sw : enabled) {
            String workflowName = null;
            String projectName = null;
            String chainId = null;
            String chainName = null;
            String targetType = sw.getTargetType().name();

            if (sw.getTargetType() == TargetType.CHAIN && sw.getChain() != null) {
                chainId = sw.getChain().getId();
                chainName = sw.getChain().getName();
            } else if (sw.getWorkflow() != null) {
                workflowName = sw.getWorkflow().getName();
                if (sw.getWorkflow().getProject() != null) {
                    projectName = sw.getWorkflow().getProject().getName();
                }
            }

            if (sw.getScheduleType() == ScheduleType.CRON) {
                int remainingRuns = getRemainingRunLimit(sw);
                if (remainingRuns == 0) {
                    continue;
                }
                Instant effectiveUntil = getEffectiveUntil(until, sw.getEndDate());
                CronExpression cron = CronExpression.parse(sw.getCronExpression());
                LocalDateTime next = cron.next(firstCursor(skipUntil));
                int addedForSchedule = 0;
                while (next != null && addedForSchedule < remainingRuns) {
                    Instant nextInstant = next.atZone(ZoneId.systemDefault()).toInstant();
                    if (!nextInstant.isBefore(effectiveUntil)) break;
                    upcoming.add(new UpcomingScheduleResponse.UpcomingRun(
                            sw.getId(), workflowName, projectName,
                            chainId, chainName, targetType, nextInstant));
                    addedForSchedule++;
                    next = cron.next(next);
                }
            } else if (sw.getScheduleType() == ScheduleType.ONCE
                    || sw.getScheduleType() == ScheduleType.DELAYED) {
                // A one-off inside the pause is dropped by the tick, not deferred.
                if (sw.getScheduledAt() != null
                        && sw.getScheduledAt().isAfter(now)
                        && sw.getScheduledAt().isBefore(until)
                        && (skipUntil.isEmpty() || !sw.getScheduledAt().isBefore(skipUntil.get()))) {
                    upcoming.add(new UpcomingScheduleResponse.UpcomingRun(
                            sw.getId(), workflowName, projectName,
                            chainId, chainName, targetType, sw.getScheduledAt()));
                }
            }
        }

        return new UpcomingScheduleResponse(upcoming.size(), upcoming, skipUntil.orElse(null), false);
    }

    /**
     * Where the occurrence search starts: now, or — during a skip-until pause — its end, with an
     * occurrence exactly at the end still counting (the tick lets it run, see
     * {@link ScheduleSkipPolicy#nextRunAfter}).
     */
    private LocalDateTime firstCursor(Optional<Instant> pause) {
        return pause.map(p -> LocalDateTime.ofInstant(p, ZoneId.systemDefault()).minusNanos(1))
                .orElseGet(() -> LocalDateTime.now(clock));
    }

    private int getRemainingRunLimit(ScheduledWorkflow sw) {
        if (sw.getMaxRuns() <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, sw.getMaxRuns() - sw.getRunCount());
    }

    private Instant getEffectiveUntil(Instant until, Instant endDate) {
        if (endDate == null || endDate.isAfter(until)) {
            return until;
        }
        return endDate;
    }

    /** The next {@code count} runs of a cron expression — after an active skip-until pause. */
    public List<Instant> getNextRuns(String cronExpression, int count) {
        if (!CronExpression.isValidExpression(cronExpression)) {
            throw new IllegalArgumentException("Invalid cron expression: " + cronExpression);
        }
        CronExpression cron = CronExpression.parse(cronExpression);
        List<Instant> result = new ArrayList<>();
        LocalDateTime next = cron.next(firstCursor(pausedUntil()));
        while (next != null && result.size() < count) {
            result.add(next.atZone(ZoneId.systemDefault()).toInstant());
            next = cron.next(next);
        }
        return result;
    }
}
