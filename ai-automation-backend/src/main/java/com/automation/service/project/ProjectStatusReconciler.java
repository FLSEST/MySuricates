package com.automation.service.project;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ContainerStatus;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.repository.ContainerRepository;
import com.automation.repository.ProjectRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Startup reconciliation for projects stuck in {@link ProjectStatus#STARTING}.
 *
 * <p>{@link ProjectService#startContainer} commits STARTING in a short burst and only then runs
 * the 5–15 s Docker create outside any transaction. If the backend dies inside that window the
 * project keeps STARTING forever: the UI offers no action for STARTING, {@code
 * DockerOrphanReconciler} only touches Docker containers, and {@code
 * ContainerService.cleanupIdleContainers} only flips projects that still have a RUNNING container
 * row. Same idea as {@code WorkflowExecutor.cleanupZombieRuns} for runs (TODO 3c119d47).
 *
 * <p>Deliberately DB-only: the container row decides the outcome (RUNNING row ⇒ RUNNING, otherwise
 * STOPPED) and no Docker call is made inside the transaction — with HikariCP pool-size 1 a Docker
 * inspect under an open transaction would pin the single connection during startup. A row that
 * lies about Docker is corrected on the next {@code ContainerService.getDockerContainerId} pass
 * (container row only) or by {@code cleanupIdleContainers} (which also flips the project); if the
 * former runs first, the project stays RUNNING until the user stops it — which the UI offers,
 * unlike STARTING.
 */
@Component
@Slf4j
public class ProjectStatusReconciler {

    private final ProjectRepository projectRepository;
    private final ContainerRepository containerRepository;
    private final ActivityLogService activityLogService;

    public ProjectStatusReconciler(ProjectRepository projectRepository,
                                   ContainerRepository containerRepository,
                                   ActivityLogService activityLogService) {
        this.projectRepository = projectRepository;
        this.containerRepository = containerRepository;
        this.activityLogService = activityLogService;
    }

    /** Clears projects left in STARTING by a previous application lifecycle. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void reconcileStartingProjects() {
        List<Project> starting = projectRepository.findByStatus(ProjectStatus.STARTING);
        if (starting.isEmpty()) return;

        log.warn("Found {} project(s) stuck in STARTING status — reconciling", starting.size());
        for (Project project : starting) {
            ProjectStatus target = containerRepository.findByProjectId(project.getId())
                    .filter(container -> container.getStatus() == ContainerStatus.RUNNING)
                    .isPresent() ? ProjectStatus.RUNNING : ProjectStatus.STOPPED;
            project.setStatus(target);
            projectRepository.save(project);
            log.info("Reconciled project {} ({}) from STARTING to {}", project.getName(), project.getId(), target);
        }
        activityLogService.warn(LogCategory.SYSTEM,
                "Startup cleanup: reconciled " + starting.size() + " project(s) stuck in STARTING",
                "ProjectStatusReconciler");
    }
}
