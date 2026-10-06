package com.automation.service.todo;

import com.automation.entity.IntegrationConnection;
import com.automation.entity.Todo;
import com.automation.exception.ErrorCode;
import com.automation.exception.ResourceNotFoundException;
import com.automation.exception.TaskExportException;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.integration.IntegrationOAuthService;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
import com.automation.service.integration.TaskExportCapable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Integration-agnostic single-TODO export: creates from a TODO an item in the external
 * tracker (GitHub issue, Jira issue, Trello card, …) and links the TODO back.
 * Provider/target come from the request override or from project.config.taskExport.
 *
 * <p>Extracted from {@code TodoService} (Audit-TODO 671add26). Does <b>not</b> own todo CRUD;
 * it only reads the todo, talks to the tracker and writes the export link back.
 *
 * <p>Deliberately not {@code @Transactional}: the OAuth token refresh and the tracker call are
 * HTTP round-trips (up to 10 s each). With HikariCP pool-size 1 they must never hold the single
 * connection, so the export runs as read burst → HTTP outside any transaction → write burst
 * (TODO f510b330). A token refresh triggered on the way is committed on its own and survives a
 * failing tracker call.
 */
@Service
@Slf4j
public class TodoTrackerExportService {

    private final TodoRepository todoRepository;
    private final ProjectRepository projectRepository;
    private final IntegrationRegistry integrationRegistry;
    private final IntegrationOAuthService integrationOAuthService;
    private final TransactionTemplate readOnlyTransactionTemplate;
    private final TransactionTemplate transactionTemplate;

    public TodoTrackerExportService(TodoRepository todoRepository,
                                    ProjectRepository projectRepository,
                                    IntegrationRegistry integrationRegistry,
                                    IntegrationOAuthService integrationOAuthService,
                                    PlatformTransactionManager transactionManager) {
        this.todoRepository = todoRepository;
        this.projectRepository = projectRepository;
        this.integrationRegistry = integrationRegistry;
        this.integrationOAuthService = integrationOAuthService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
    }

    /** Everything the tracker call needs, copied out of the read burst (no entity crosses the boundary). */
    private record ExportPlan(TaskExportCapable.TaskRef existing, String providerId,
                              TaskExportCapable taskProvider, TaskExportCapable.TaskExportInput input) {
        static ExportPlan alreadyExported(TaskExportCapable.TaskRef existing) {
            return new ExportPlan(existing, null, null, null);
        }
    }

    public TaskExportCapable.TaskRef exportToTracker(String id, String providerOverride, String targetOverride) {
        ExportPlan plan = readOnlyTransactionTemplate.execute(status ->
                planExport(id, providerOverride, targetOverride));
        if (plan.existing() != null) {
            return plan.existing();
        }

        // Outside any transaction: may refresh the OAuth token (HTTP + own DB write) and calls the tracker (HTTP).
        String token = integrationOAuthService.getValidAccessToken(plan.providerId());
        TaskExportCapable.TaskRef ref = plan.taskProvider().createTask(plan.input(), token);

        return transactionTemplate.execute(status -> writeBackExportLink(id, plan.providerId(), ref));
    }

    /** Read burst: loads the todo, resolves provider/target and validates — no network I/O. */
    private ExportPlan planExport(String id, String providerOverride, String targetOverride) {
        Todo todo = todoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Todo", id));

        // Idempotent: already exported to a tracker -> return the existing export link, no duplicate.
        // (Deliberately ONLY exported_*; referenceType stays reserved for internal/origin correlation.)
        if (isNotBlank(todo.getExportedProvider()) && isNotBlank(todo.getExportedRef())) {
            return ExportPlan.alreadyExported(existingRef(todo));
        }

        Map<String, Object> taskExport = readTaskExportConfig(todo.getProjectId());
        String providerId = firstNonBlank(providerOverride, asString(taskExport.get("provider")));
        String target = firstNonBlank(targetOverride, asString(taskExport.get("target")));

        if (providerId == null) {
            throw new TaskExportException(ErrorCode.NO_TASK_TARGET,
                    "Kein Ziel-Tracker konfiguriert (project.config.taskExport.provider)",
                    Map.of("setting", "project.config.taskExport.provider"));
        }
        if (!integrationRegistry.hasProvider(providerId)) {
            throw new TaskExportException(ErrorCode.PROVIDER_NOT_TASK_CAPABLE, "Unbekannte Integration: " + providerId,
                    Map.of("provider", providerId));
        }
        IntegrationProvider provider = integrationRegistry.getProvider(providerId);
        if (!(provider instanceof TaskExportCapable taskProvider)) {
            throw new TaskExportException(ErrorCode.PROVIDER_NOT_TASK_CAPABLE,
                    "Integration '" + providerId + "' kann keine Tasks aufnehmen",
                    Map.of("provider", providerId));
        }
        boolean connected = integrationOAuthService.getConnection(providerId)
                .map(c -> c.getStatus() == IntegrationConnection.ConnectionStatus.CONNECTED)
                .orElse(false);
        if (!connected) {
            throw new TaskExportException(ErrorCode.PROVIDER_NOT_CONNECTED,
                    "Integration '" + providerId + "' ist nicht verbunden",
                    Map.of("provider", providerId));
        }
        if (target == null) {
            throw new TaskExportException(ErrorCode.NO_TASK_TARGET,
                    "Kein Ziel für '" + providerId + "' konfiguriert (project.config.taskExport.target)",
                    Map.of("setting", "project.config.taskExport.target"));
        }

        var input = new TaskExportCapable.TaskExportInput(
                todo.getTitle(), todo.getDescription(), parseTags(todo.getTags()), target);
        return new ExportPlan(null, providerId, taskProvider, input);
    }

    /** Write burst: re-loads the todo and stores the export link (kept separate from referenceType). */
    private TaskExportCapable.TaskRef writeBackExportLink(String id, String providerId, TaskExportCapable.TaskRef ref) {
        Todo todo = todoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Todo", id));
        if (isNotBlank(todo.getExportedProvider()) && isNotBlank(todo.getExportedRef())) {
            // Exported concurrently while our tracker call was in flight: keep the first link.
            log.warn("Todo {} was exported to {} {} concurrently; keeping that link, {} {} stays unlinked",
                    todo.getId(), todo.getExportedProvider(), todo.getExportedRef(), ref.referenceType(), ref.referenceId());
            return existingRef(todo);
        }
        todo.setExportedProvider(ref.referenceType());
        todo.setExportedRef(ref.referenceId());
        todo.setExportedUrl(ref.url());
        todoRepository.save(todo);
        log.info("Exported todo {} ({}) to {} -> {}", todo.getId(), todo.getTitle(), providerId, ref.referenceId());
        return ref;
    }

    private static TaskExportCapable.TaskRef existingRef(Todo todo) {
        return new TaskExportCapable.TaskRef(todo.getExportedProvider(), todo.getExportedRef(), todo.getExportedUrl());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readTaskExportConfig(String projectId) {
        if (projectId == null || projectId.isBlank()) return Map.of();
        return projectRepository.findById(projectId)
                .map(p -> p.getConfig())
                .map(cfg -> cfg.get("taskExport"))
                .filter(v -> v instanceof Map)
                .map(v -> (Map<String, Object>) v)
                .orElse(Map.of());
    }

    /** Lenient parse of the stored tags field (JSON array or CSV) into a label list. */
    static List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        String stripped = tags.trim().replaceAll("^\\[|\\]$", "");
        List<String> out = new ArrayList<>();
        for (String part : stripped.split(",")) {
            String s = part.trim().replaceAll("^\"|\"$", "");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String asString(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String a, String b) {
        if (isNotBlank(a)) return a;
        return isNotBlank(b) ? b : null;
    }
}
