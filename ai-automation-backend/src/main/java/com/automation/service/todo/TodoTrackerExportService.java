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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Integrations-agnostischer Single-TODO-Export: legt aus einem TODO ein Item im externen
 * Tracker an (GitHub-Issue, Jira-Issue, Trello-Card, …) und verknüpft das TODO zurück.
 * Provider/Ziel kommen aus dem Request-Override oder aus project.config.taskExport.
 *
 * <p>Extracted from {@code TodoService} (Audit-TODO 671add26). Does <b>not</b> own todo CRUD;
 * it only reads the todo, talks to the tracker and writes the export link back.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TodoTrackerExportService {

    private final TodoRepository todoRepository;
    private final ProjectRepository projectRepository;
    private final IntegrationRegistry integrationRegistry;
    private final IntegrationOAuthService integrationOAuthService;

    @Transactional
    public TaskExportCapable.TaskRef exportToTracker(String id, String providerOverride, String targetOverride) {
        Todo todo = todoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Todo", id));

        // Idempotent: schon an einen Tracker exportiert -> bestehenden Export-Link zurueckgeben, kein Duplikat.
        // (Bewusst NUR exported_*; referenceType bleibt fuer interne/Origin-Korrelation reserviert.)
        if (isNotBlank(todo.getExportedProvider()) && isNotBlank(todo.getExportedRef())) {
            return new TaskExportCapable.TaskRef(todo.getExportedProvider(), todo.getExportedRef(), todo.getExportedUrl());
        }

        Map<String, Object> taskExport = readTaskExportConfig(todo.getProjectId());
        String providerId = firstNonBlank(providerOverride, asString(taskExport.get("provider")));
        String target = firstNonBlank(targetOverride, asString(taskExport.get("target")));

        if (providerId == null) {
            throw new TaskExportException(ErrorCode.NO_TASK_TARGET,
                    "Kein Ziel-Tracker konfiguriert (project.config.taskExport.provider)");
        }
        if (!integrationRegistry.hasProvider(providerId)) {
            throw new TaskExportException(ErrorCode.PROVIDER_NOT_TASK_CAPABLE, "Unbekannte Integration: " + providerId);
        }
        IntegrationProvider provider = integrationRegistry.getProvider(providerId);
        if (!(provider instanceof TaskExportCapable taskProvider)) {
            throw new TaskExportException(ErrorCode.PROVIDER_NOT_TASK_CAPABLE,
                    "Integration '" + providerId + "' kann keine Tasks aufnehmen");
        }
        boolean connected = integrationOAuthService.getConnection(providerId)
                .map(c -> c.getStatus() == IntegrationConnection.ConnectionStatus.CONNECTED)
                .orElse(false);
        if (!connected) {
            throw new TaskExportException(ErrorCode.PROVIDER_NOT_CONNECTED,
                    "Integration '" + providerId + "' ist nicht verbunden");
        }
        if (target == null) {
            throw new TaskExportException(ErrorCode.NO_TASK_TARGET,
                    "Kein Ziel für '" + providerId + "' konfiguriert (project.config.taskExport.target)");
        }

        String token = integrationOAuthService.getValidAccessToken(providerId);
        var input = new TaskExportCapable.TaskExportInput(
                todo.getTitle(), todo.getDescription(), parseTags(todo.getTags()), target);
        TaskExportCapable.TaskRef ref = taskProvider.createTask(input, token);

        // Export-Link getrennt von referenceType ablegen (kein Ueberschreiben interner Marker).
        todo.setExportedProvider(ref.referenceType());
        todo.setExportedRef(ref.referenceId());
        todo.setExportedUrl(ref.url());
        todoRepository.save(todo);
        log.info("Exported todo {} ({}) to {} -> {}", todo.getId(), todo.getTitle(), providerId, ref.referenceId());
        return ref;
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
