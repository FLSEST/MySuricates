package com.automation.service.todo;

import com.automation.config.TodoHygieneConfig;
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
 * Daily archival of stale auto-generated todos.
 *
 * Moves OPEN/BACKLOG machine todos tagged {@code auto:workflow} with no activity
 * (updatedAt) for {@code staleDays} into the ARCHIVED status — keeping the active
 * list focused on what still matters. ARCHIVED is reversible (visible via the
 * cockpit/Archiv tab), so this is low-stakes.
 *
 * Dormant by default ({@code enabled=false}); the cockpit's one-click
 * "Verstaubtes archivieren" is the primary, human-in-the-loop path. The job only
 * runs once a user opts in via the {@code todo-hygiene-enabled} setting.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TodoHygieneService {

    private final TodoRepository todoRepository;
    private final ActivityLogService activityLogService;
    private final TodoHygieneConfig config;

    @Scheduled(cron = "0 30 3 * * *")  // daily 03:30, after RunCleanup (03:00)
    @Transactional
    public void archiveStaleAutoTodos() {
        if (!config.isEnabled()) {
            return;
        }
        archiveStaleNow(null);
    }

    /**
     * Archive stale auto-todos now (user-triggered from the cockpit, or by the scheduled job).
     * Works regardless of the {@code enabled} flag — that flag only gates the automatic schedule.
     *
     * @param projectId optional project scope; null/blank archives across all projects
     * @return number of todos archived
     */
    @Transactional
    public int archiveStaleNow(String projectId) {
        Instant cutoff = Instant.now().minus(config.getStaleDays(), ChronoUnit.DAYS);
        PageRequest limit = PageRequest.of(0, config.getMaxPerRun());
        List<String> ids = (projectId == null || projectId.isBlank())
                ? todoRepository.findStaleAutoTodoIds(cutoff, limit)
                : todoRepository.findStaleAutoTodoIdsForProject(projectId, cutoff, limit);
        if (ids.isEmpty()) {
            return 0;
        }
        int archived = todoRepository.bulkSetStatus(ids, Todo.Status.ARCHIVED, Instant.now());
        String scope = (projectId == null || projectId.isBlank()) ? "" : " (Projekt " + projectId + ")";
        log.info("Todo-Hygiene: archived {} stale auto-todo(s) older than {} days{}",
                archived, config.getStaleDays(), scope);
        activityLogService.info(LogCategory.SYSTEM,
                "Todo-Hygiene: " + archived + " verstaubte Auto-Todos archiviert" + scope
                        + " (älter als " + config.getStaleDays() + " Tagen)",
                "TodoHygieneService");
        return archived;
    }
}
