package com.automation.service.assistant.context;

import com.automation.entity.ScheduledWorkflow;
import com.automation.service.scheduler.UpcomingRunsCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The schedules of the next 24 hours for the assistant — with the run that really comes: during a
 * skip-until pause the stored next run is skipped, not started (TODO a776fd2a).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchedulesContextSection implements AssistantContextSection {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());

    private final UpcomingRunsCalculator upcomingRuns;

    @Override
    public void append(StringBuilder sb, boolean compact) {
        try {
            Instant now = Instant.now();
            List<ScheduledWorkflow> upcoming = upcomingRuns.findWithNextRunBetween(now, now.plusSeconds(86400));
            if (compact) {
                if (!upcoming.isEmpty()) {
                    sb.append("**Schedules:** ").append(upcoming.size()).append(" upcoming (next 24h)\n");
                }
                return;
            }
            if (!upcoming.isEmpty()) {
                sb.append("**Upcoming Schedules (next 24h):**\n");
                upcoming.forEach(s -> {
                    String name = s.getWorkflow() != null ? s.getWorkflow().getName() : s.getTargetName();
                    sb.append("  - ").append(name).append(": ").append(fmt(upcomingRuns.nextRun(s))).append("\n");
                });
                sb.append("\n");
            }
        } catch (Exception e) {
            log.debug("Could not load schedules for assistant context", e);
        }
    }

    private String fmt(Instant instant) {
        return instant != null ? FMT.format(instant) : "—";
    }
}
