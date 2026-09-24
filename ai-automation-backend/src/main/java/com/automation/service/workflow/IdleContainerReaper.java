package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import com.automation.entity.ProjectStatus;
import com.automation.repository.ContainerRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.project.DockerContainerGateway;
import com.automation.service.project.DockerExpectation;
import com.automation.service.project.DockerOrphanReconciler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Stoppt Projekt-Container, die länger als {@code docker.container.idle-timeout-minutes} ohne
 * Aktivität laufen, und lässt vorher {@link DockerOrphanReconciler} verwaiste Docker-Container
 * einsammeln. Container mit aktivem Run ({@link WorkflowExecutionRegistry}) bleiben unberührt.
 * Liegt in {@code service.workflow}, weil es den Run-Zustand liest — so hängt
 * {@code service.project} dafür nicht an {@code service.workflow}.
 */
@Component
@Slf4j
public class IdleContainerReaper {

    private final ContainerRepository containerRepository;
    private final WorkflowExecutionRegistry executionRegistry;
    private final ActivityLogService activityLogService;
    private final DockerContainerGateway docker;
    private final DockerOrphanReconciler orphanReconciler;
    private final DockerExpectation dockerExpectation;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readOnlyTransactionTemplate;
    private final int idleTimeoutMinutes;

    public IdleContainerReaper(
            ContainerRepository containerRepository,
            WorkflowExecutionRegistry executionRegistry,
            ActivityLogService activityLogService,
            PlatformTransactionManager transactionManager,
            DockerContainerGateway docker,
            DockerOrphanReconciler orphanReconciler,
            DockerExpectation dockerExpectation,
            @Value("${docker.container.idle-timeout-minutes}") int idleTimeoutMinutes) {
        this.containerRepository = containerRepository;
        this.executionRegistry = executionRegistry;
        this.activityLogService = activityLogService;
        this.docker = docker;
        this.orphanReconciler = orphanReconciler;
        this.dockerExpectation = dockerExpectation;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
        this.idleTimeoutMinutes = idleTimeoutMinutes;
    }

    // NOT @Transactional at the method level: each iteration does a Docker
    // stop+remove (multi-second). The previous implementation wrapped the
    // whole loop in a single transaction, so cleaning up N idle containers
    // held the pool's only connection for N×~5s. We now use short
    // TransactionTemplate bursts per container.
    @Scheduled(fixedRate = 300_000)
    public void cleanupIdleContainers() {
        if (!dockerExpectation.isConfigured()) return; // ohne Docker nichts aufzuraeumen (a0e85182)
        List<Container> running = readOnlyTransactionTemplate.execute(status ->
                containerRepository.findByStatus(ContainerStatus.RUNNING));
        Set<String> activeContainers = executionRegistry.getActiveContainerIds();

        orphanReconciler.reconcile(running, activeContainers);
        if (running == null || running.isEmpty()) return;

        Instant threshold = Instant.now().minus(Duration.ofMinutes(idleTimeoutMinutes));

        for (Container container : running) {
            if (activeContainers.contains(container.getDockerContainerId())) {
                log.debug("Skipping cleanup for container with active execution: {}",
                        container.getDockerContainerId());
                continue;
            }
            Instant lastActivity = container.getLastActivityAt() != null
                    ? container.getLastActivityAt()
                    : container.getStartedAt();
            if (lastActivity != null && lastActivity.isBefore(threshold)) {
                log.info("Stopping idle container: {}", container.getDockerContainerId());
                docker.remove(container.getDockerContainerId());

                final String entityId = container.getId();
                transactionTemplate.executeWithoutResult(status ->
                        containerRepository.findById(entityId).ifPresent(c -> {
                            c.setStatus(ContainerStatus.STOPPED);
                            c.setStoppedAt(Instant.now());
                            containerRepository.save(c);
                            if (c.getProject() != null) {
                                c.getProject().setStatus(ProjectStatus.STOPPED);
                            }
                        }));
                activityLogService.info(LogCategory.CONTAINER,
                        "Idle container cleaned up: " + container.getDockerContainerId(),
                        "ContainerService");
            }
        }
    }
}
