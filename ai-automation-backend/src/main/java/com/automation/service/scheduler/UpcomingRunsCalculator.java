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

/**
 * Projects enabled schedules onto the calendar: which runs are still due today / this week,
 * and the next occurrences of a cron expression.
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
                LocalDateTime next = cron.next(LocalDateTime.now(clock));
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
                if (sw.getScheduledAt() != null
                        && sw.getScheduledAt().isAfter(now)
                        && sw.getScheduledAt().isBefore(until)) {
                    upcoming.add(new UpcomingScheduleResponse.UpcomingRun(
                            sw.getId(), workflowName, projectName,
                            chainId, chainName, targetType, sw.getScheduledAt()));
                }
            }
        }

        return new UpcomingScheduleResponse(upcoming.size(), upcoming);
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

    public List<Instant> getNextRuns(String cronExpression, int count) {
        if (!CronExpression.isValidExpression(cronExpression)) {
            throw new IllegalArgumentException("Invalid cron expression: " + cronExpression);
        }
        CronExpression cron = CronExpression.parse(cronExpression);
        List<Instant> result = new ArrayList<>();
        LocalDateTime next = cron.next(LocalDateTime.now(clock));
        while (next != null && result.size() < count) {
            result.add(next.atZone(ZoneId.systemDefault()).toInstant());
            next = cron.next(next);
        }
        return result;
    }
}
