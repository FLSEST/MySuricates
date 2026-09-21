package com.automation.service.health;

import com.automation.config.StagingConfig;
import com.automation.entity.Todo;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.staging.DockerContainerManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class HealthAutoTodoService {

    private static final String REFERENCE_TYPE = "health_check";

    private final StagingConfig stagingConfig;
    private final DockerContainerManager docker;
    private final TodoRepository todoRepository;
    private final ProjectRepository projectRepository;
    private final ModuleRegistry modules;

    @Value("${automation.autopilot.project-id:}")
    private String autopilotProjectId;

    /** What one tick sees of an instance. */
    enum InstanceState {
        /** Health endpoint answers. */
        UP,
        /** Container exists but the endpoint is dead: crashed, hanging or still starting. */
        DOWN,
        /**
         * No container: never set up on this installation (single-instance install), or
         * deliberately stopped — {@code docker compose down} (staging.sh stop, promote) removes it.
         */
        ABSENT
    }

    /** Last state per instance; missing = not looked at yet in this process. */
    private final Map<String, InstanceState> lastState = new ConcurrentHashMap<>();

    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void checkInstanceHealth() {
        // Without the staging module there are no stable/test instances to watch — probing
        // them would only file "instance unhealthy" TODOs on every fresh clone.
        if (!modules.isEnabled(ModuleCatalog.STAGING)) {
            return;
        }
        checkInstance("stable", "Stable", observe(stagingConfig.getHealthUrl(), stagingConfig.getContainerName()));
        checkInstance("test", "Test", observe(stagingConfig.getTestHealthUrl(), stagingConfig.getTestContainerName()));
    }

    private InstanceState observe(String healthUrl, String containerName) {
        if (docker.isHealthy(healthUrl)) {
            return InstanceState.UP;
        }
        return docker.containerExists(containerName) ? InstanceState.DOWN : InstanceState.ABSENT;
    }

    private void checkInstance(String refId, String label, InstanceState current) {
        InstanceState previous = lastState.get(refId);
        if (current == previous) {
            return;
        }
        try {
            if (current == InstanceState.DOWN) {
                // Only an instance seen UP in this process went down. From ABSENT it is still
                // starting; on the first look (e.g. after a reboot) it just was not started yet.
                if (previous == InstanceState.UP) {
                    openTodo(refId, label);
                }
            } else {
                // UP: recovered. ABSENT: removed or deliberately stopped — nothing left to fix.
                // Also closes a todo left over from before a restart.
                closeTodo(refId, label, current);
            }
            // Only update state after persistence succeeded — if save() throws,
            // the next tick will re-detect the transition and retry
            lastState.put(refId, current);
        } catch (Exception e) {
            log.error("Failed to process health transition for {}: {}", label, e.getMessage());
        }
    }

    private void openTodo(String refId, String label) {
        if (todoRepository.findOpenByReferenceTypeAndId(REFERENCE_TYPE, refId).isPresent()) {
            return;
        }
        String projectId = (autopilotProjectId != null && !autopilotProjectId.isBlank()
                && projectRepository.existsById(autopilotProjectId)) ? autopilotProjectId : null;
        Todo todo = Todo.builder()
                .title("[Health] " + label + "-Instanz nicht erreichbar")
                .description("Die " + label + "-Instanz ist nicht mehr erreichbar. "
                        + "Bitte Status prüfen und ggf. neu starten.")
                .priority(Todo.Priority.HIGH)
                .source(Todo.Source.AI)
                .sourceAgent("health-monitor")
                .projectId(projectId)
                .tags("auto:health,instance:" + refId)
                .referenceType(REFERENCE_TYPE)
                .referenceId(refId)
                .build();
        todoRepository.save(todo);
        log.info("Created health todo for {} instance", label);
    }

    private void closeTodo(String refId, String label, InstanceState current) {
        todoRepository.findOpenByReferenceTypeAndId(REFERENCE_TYPE, refId).ifPresent(todo -> {
            todo.setStatus(Todo.Status.DONE);
            todoRepository.save(todo);
            log.info("Closed health todo for {} instance ({})", label,
                    current == InstanceState.UP ? "recovered" : "no container any more");
        });
    }
}
