package com.automation.service.health;

import com.automation.entity.Todo;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.staging.StagingService;
import com.automation.service.staging.TestingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class HealthAutoTodoService {

    private static final String REFERENCE_TYPE = "health_check";
    private static final String AUTOPILOT_PROJECT_ID = "ebf4f50c-20c2-4c35-a84a-a0b350124523";

    private final StagingService stagingService;
    private final TestingService testingService;
    private final TodoRepository todoRepository;
    private final ProjectRepository projectRepository;

    private volatile boolean previousStableHealthy = true;
    private volatile boolean previousTestHealthy = true;

    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void checkInstanceHealth() {
        checkInstance("stable", stagingService.isHealthy(), previousStableHealthy, "Stable");
        checkInstance("test", testingService.isHealthy(), previousTestHealthy, "Test");
    }

    private void checkInstance(String refId, boolean currentlyHealthy, boolean previouslyHealthy, String label) {
        try {
            if (previouslyHealthy && !currentlyHealthy) {
                // Transition healthy → unhealthy: create todo if none open
                Optional<Todo> existing = todoRepository.findOpenByReferenceTypeAndId(REFERENCE_TYPE, refId);
                if (existing.isEmpty()) {
                    String projectId = projectRepository.existsById(AUTOPILOT_PROJECT_ID) ? AUTOPILOT_PROJECT_ID : null;
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
            } else if (!previouslyHealthy && currentlyHealthy) {
                // Transition unhealthy → healthy: close open todo
                todoRepository.findOpenByReferenceTypeAndId(REFERENCE_TYPE, refId).ifPresent(todo -> {
                    todo.setStatus(Todo.Status.DONE);
                    todoRepository.save(todo);
                    log.info("Closed health todo for {} instance (recovered)", label);
                });
            }

            // Only update state after persistence succeeded — if save() throws,
            // the next tick will re-detect the transition and retry
            if (refId.equals("stable")) {
                previousStableHealthy = currentlyHealthy;
            } else {
                previousTestHealthy = currentlyHealthy;
            }
        } catch (Exception e) {
            log.error("Failed to process health transition for {}: {}", label, e.getMessage());
        }
    }
}
