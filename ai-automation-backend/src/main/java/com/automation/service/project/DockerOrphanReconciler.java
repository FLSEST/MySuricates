package com.automation.service.project;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.Container;
import com.automation.service.activitylog.ActivityLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * State-drift reconciliation: Docker containers named automation-* that are RUNNING
 * in Docker but have no RUNNING row in the containers table are invisible to the
 * DB-driven idle cleanup and would live forever. Such orphans are force-stopped;
 * they restart lazily on the next container workflow (~5-15s cold start).
 * A grace period (idleTimeoutMinutes, based on Docker's created timestamp) protects
 * containers whose DB row is still being written.
 *
 * <p>Extracted from {@code ContainerService} (Audit-TODO b804e1be).
 */
@Component
@Slf4j
public class DockerOrphanReconciler {

    private final DockerContainerGateway docker;
    private final ActivityLogService activityLogService;
    private final int idleTimeoutMinutes;

    public DockerOrphanReconciler(
            DockerContainerGateway docker,
            ActivityLogService activityLogService,
            @Value("${docker.container.idle-timeout-minutes}") int idleTimeoutMinutes) {
        this.docker = docker;
        this.activityLogService = activityLogService;
        this.idleTimeoutMinutes = idleTimeoutMinutes;
    }

    public void reconcile(List<Container> runningRows, Set<String> activeContainers) {
        List<com.github.dockerjava.api.model.Container> dockerRunning;
        try {
            dockerRunning = docker.listAutomationContainers();
        } catch (Exception e) {
            log.warn("Container reconcile: docker list failed: {}", e.getMessage());
            return;
        }
        if (dockerRunning == null || dockerRunning.isEmpty()) return;

        Set<String> knownRunningIds = runningRows == null ? Set.of() : runningRows.stream()
                .map(Container::getDockerContainerId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        Instant graceCutoff = Instant.now().minus(Duration.ofMinutes(idleTimeoutMinutes));

        for (var dockerContainer : dockerRunning) {
            String id = dockerContainer.getId();
            if (id == null || knownRunningIds.contains(id) || activeContainers.contains(id)) continue;
            if (dockerContainer.getCreated() != null && Instant.ofEpochSecond(dockerContainer.getCreated()).isAfter(graceCutoff)) {
                continue; // startup race: DB row may not be committed yet
            }
            log.warn("Container reconcile: orphaned Docker container {} has no RUNNING DB row — force-stopping", id);
            docker.remove(id);
            activityLogService.warn(LogCategory.CONTAINER,
                    "Reconcile: verwaister Docker-Container ohne DB-Eintrag gestoppt: " + id,
                    "ContainerService");
        }
    }
}
