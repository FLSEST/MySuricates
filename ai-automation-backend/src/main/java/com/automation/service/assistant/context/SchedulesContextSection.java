package com.automation.service.assistant.context;

import com.automation.entity.ScheduledWorkflow;
import com.automation.repository.ScheduledWorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class SchedulesContextSection implements AssistantContextSection {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());

    private final ScheduledWorkflowRepository scheduledWorkflowRepository;

    @Override
    public void append(StringBuilder sb, boolean compact) {
        try {
            List<ScheduledWorkflow> upcoming = scheduledWorkflowRepository
                    .findSchedulesInRange(Instant.now(), Instant.now().plusSeconds(86400));
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
                    sb.append("  - ").append(name).append(": ").append(fmt(s.getNextRunAt())).append("\n");
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
