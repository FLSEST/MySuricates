package com.automation.service.workflow;

import com.automation.service.project.ContainerService;
import com.automation.entity.Project;
import com.automation.entity.Container;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class RunContainerPreparation {
    static String ensureRunning(Project project, String runId, ContainerService containerService,
                                ExecutionBroadcaster broadcaster) {
        // Without Docker set up, do not even try: the run executes on the host anyway, and every
        // failed attempt wrote an activity log ERROR (a0e85182).
        if (!containerService.isDockerConfigured()) {
            log.debug("Docker not configured — run {} executes on the host", runId);
            return null;
        }

        // Try to get existing running container
        try {
            return containerService.getDockerContainerId(project);
        } catch (Exception e) {
            // Expected after IdleContainerReaper stopped an idle container, so not a warning (cbf57f21)
            log.info("No running container for project {} ({}), attempting to start one...",
                    project.getName(), e.getMessage());
        }

        // Try to start a new container
        try {
            broadcaster.broadcastLogMessage(runId, "container_starting",
                    Map.of("message", "Container wird gestartet für Projekt: " + project.getName()));

            Container container = containerService.createAndStartContainer(project);

            broadcaster.broadcastLogMessage(runId, "container_started",
                    Map.of("message", "Container gestartet", "containerId", container.getDockerContainerId()));

            return container.getDockerContainerId();
        } catch (Exception e) {
            // Container creation failed — continue without container (host-based execution still works)
            log.warn("Failed to start container for project {} — continuing with host-based execution: {}",
                    project.getName(), e.getMessage());
            broadcaster.broadcastLogMessage(runId, "container_skipped",
                    Map.of("message", "Container konnte nicht gestartet werden, Ausführung erfolgt auf dem Host"));
            return null;
        }
    }
}
