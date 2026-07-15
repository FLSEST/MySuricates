package com.automation.service.todo;

import com.automation.config.TodoGhostWatchdogConfig;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.Todo;
import com.automation.repository.TodoRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Periodic watchdog for orphaned IN_PROGRESS todos — the todo-side sibling of
 * {@link com.automation.service.workflow.GhostRunWatchdogService}.
 *
 * When a CLI session (Claude Code, Codex) dies without its closing
 * {@code PATCH /todos/{id}/status DONE}, the todo sits IN_PROGRESS forever.
 * This watchdog resets machine-sourced (AI/WORKFLOW) todos back to OPEN once they
 * exceed the staleness threshold, so they become visible and actionable again.
 * Human todos (source USER) are never touched — people may legitimately keep
 * items IN_PROGRESS for days.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TodoGhostWatchdogService {

    private final TodoRepository todoRepository;
    private final ActivityLogService activityLogService;
    private final TodoGhostWatchdogConfig config;

    @Scheduled(fixedDelayString = "${automation.todo-watchdog.poll-ms:300000}", initialDelay = 60_000)
    @Transactional
    public void resetStaleInProgressTodos() {
        if (!config.isEnabled()) {
            return;
        }
        Instant now = Instant.now();
        Instant cutoff = now.minus(config.getStaleHours(), ChronoUnit.HOURS);
        List<String> ids = todoRepository.findStaleInProgressAutoTodoIds(
                cutoff, PageRequest.of(0, config.getMaxPerRun()));
        if (ids.isEmpty()) {
            return;
        }
        int reset = todoRepository.bulkSetStatus(ids, Todo.Status.OPEN, now);
        log.warn("Todo-Watchdog: {} verwaiste IN_PROGRESS-TODO(s) auf OPEN zurückgesetzt (älter als {}h): {}",
                reset, config.getStaleHours(), ids);
        activityLogService.warn(LogCategory.SYSTEM,
                "Todo-Watchdog: " + reset + " verwaiste IN_PROGRESS-TODO(s) auf OPEN zurückgesetzt"
                        + " (keine Aktivität seit " + config.getStaleHours() + "h)",
                "TodoGhostWatchdogService");
    }
}
