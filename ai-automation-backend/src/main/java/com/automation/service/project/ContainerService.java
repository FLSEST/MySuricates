package com.automation.service.project;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import com.automation.entity.Project;
import com.automation.exception.ContainerException;
import com.automation.repository.ContainerRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.workflow.WorkflowExecutionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifecycle of project containers (create/reuse, stop, idle cleanup) and the containers table.
 * Docker calls go through {@link DockerContainerGateway}, orphan reconciliation through
 * {@link DockerOrphanReconciler}; every DB access is a short {@link TransactionTemplate} burst
 * because the slow Docker work must never hold the single SQLite connection.
 */
@Service
@Slf4j
public class ContainerService {

    private final ContainerRepository containerRepository;
    private final WorkflowExecutionRegistry executionRegistry;
    private final ActivityLogService activityLogService;
    private final PathValidator pathValidator;
    private final DockerContainerGateway docker;
    private final DockerOrphanReconciler orphanReconciler;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readOnlyTransactionTemplate;

    private final int idleTimeoutMinutes;
    private final String hostHome;

    // Per-project lock to serialize concurrent container creation for the same
    // project. Without this, two parallel workflow runs both pass the
    // findByProjectId check, both call Docker create, and the second INSERT
    // fails with UNIQUE constraint on containers.project_id (leaving an
    // orphan Docker container behind).
    private final ConcurrentHashMap<String, Object> projectLocks = new ConcurrentHashMap<>();

    public ContainerService(
            ContainerRepository containerRepository,
            WorkflowExecutionRegistry executionRegistry,
            ActivityLogService activityLogService,
            PathValidator pathValidator,
            PlatformTransactionManager transactionManager,
            DockerContainerGateway docker,
            DockerOrphanReconciler orphanReconciler,
            @Value("${docker.container.idle-timeout-minutes}") int idleTimeoutMinutes,
            @Value("${automation.cli.host-home:}") String hostHome) {
        this.containerRepository = containerRepository;
        this.executionRegistry = executionRegistry;
        this.activityLogService = activityLogService;
        this.pathValidator = pathValidator;
        this.docker = docker;
        this.orphanReconciler = orphanReconciler;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
        this.idleTimeoutMinutes = idleTimeoutMinutes;
        this.hostHome = hostHome != null && !hostHome.isBlank()
                ? hostHome : System.getProperty("user.home");
    }

    // NOT @Transactional: Docker create/start can take 5-15s. With pool-size=1
    // (SQLite), an enclosing transaction would hold the single connection for
    // the whole Docker call, blocking every other request and causing the
    // browser-side "Failed to fetch" the user reported. Instead we run the
    // Docker work outside any transaction and use TransactionTemplate for the
    // short DB bursts (find existing / delete stale row / insert new row).
    public Container createAndStartContainer(Project project) {
        Object lock = projectLocks.computeIfAbsent(project.getId(), k -> new Object());
        try {
            synchronized (lock) {
                return doCreateAndStart(project);
            }
        } finally {
            projectLocks.remove(project.getId(), lock);
        }
    }

    private Container doCreateAndStart(Project project) {
        // Defense in depth: revalidate the project path right before the host bind
        // mount. Project-create already validated, but a DB tamper, migration or
        // skipped validator at update time must not let a sensitive host path
        // (`/etc`, `~/.ssh`, ...) reach Bind.parse below.
        pathValidator.validateForContainerMount(project.getPath());

        // Same check on hostHome: protects against AUTOMATION_CLI_HOST_HOME being
        // set to /etc or ~/.ssh in misconfigured environments. hostHome is a
        // server-side property, not user input, so this is a misconfiguration
        // guard rather than an attack mitigation — but it fails fast with a
        // clear message instead of mounting secrets into the container.
        pathValidator.validateForContainerMount(hostHome);

        // Step 1 (short Tx, read-only): look up the existing Container row for this project.
        Container existing = readOnlyTransactionTemplate.execute(status ->
                containerRepository.findByProjectId(project.getId()).orElse(null));

        // Step 2 (no Tx): if it already runs in Docker, return it; otherwise mark
        // for cleanup. isRunning() is a Docker call (slow).
        String oldDockerContainerId = null;
        String oldEntityId = null;
        if (existing != null) {
            if (existing.getStatus() == ContainerStatus.RUNNING
                    && docker.isRunning(existing.getDockerContainerId())) {
                return existing;
            }
            oldDockerContainerId = existing.getDockerContainerId();
            oldEntityId = existing.getId();
        }

        // Step 3 (no Tx): stop & remove the old Docker container — slow.
        if (oldDockerContainerId != null) {
            docker.remove(oldDockerContainerId);
        }

        // Step 4 (short Tx): drop the old DB row so the new save does not hit
        // the UNIQUE(containers.project_id) constraint.
        if (oldEntityId != null) {
            final String entityId = oldEntityId;
            transactionTemplate.executeWithoutResult(status -> {
                containerRepository.deleteById(entityId);
                containerRepository.flush();
            });
        }

        String imageName = DockerContainerGateway.imageFor(project.getType());
        String containerName = "automation-" + project.getId();

        // Step 5 (no Tx): clear any leftover Docker container with the same name.
        docker.remove(containerName);

        try {
            // Step 6 (no Tx): create + start the Docker container — the slow
            // operation we are explicitly keeping out of a transaction.
            String dockerContainerId = docker.createAndStart(project, imageName, containerName, hostHome);

            // Step 7 (short Tx): persist the new Container row.
            Container saved = transactionTemplate.execute(status -> {
                Container container = Container.builder()
                        .project(project)
                        .dockerContainerId(dockerContainerId)
                        .imageName(imageName)
                        .status(ContainerStatus.RUNNING)
                        .mounts(Map.of("workspace", project.getPath()))
                        .startedAt(Instant.now())
                        .lastActivityAt(Instant.now())
                        .build();
                return containerRepository.save(container);
            });
            project.setContainer(saved);

            log.info("Started container {} for project {}", dockerContainerId, project.getId());
            activityLogService.info(LogCategory.CONTAINER,
                    "Container started for project: " + project.getName(),
                    "ContainerService", project.getId());
            return saved;

        } catch (Exception e) {
            activityLogService.error(LogCategory.CONTAINER,
                    "Failed to create container for project: " + project.getName() + " - " + e.getMessage(),
                    "ContainerService", project.getId());
            throw new ContainerException("Failed to create container for project: " + project.getId(), e);
        }
    }

    // NOT @Transactional: docker.remove() does Docker stop+remove
    // (multi-second). See createAndStartContainer for the rationale.
    public void stopContainer(Project project) {
        Container container = readOnlyTransactionTemplate.execute(status ->
                containerRepository.findByProjectId(project.getId()).orElse(null));
        if (container == null) return;

        docker.remove(container.getDockerContainerId());

        final String entityId = container.getId();
        transactionTemplate.executeWithoutResult(status ->
                containerRepository.findById(entityId).ifPresent(c -> {
                    c.setStatus(ContainerStatus.STOPPED);
                    c.setStoppedAt(Instant.now());
                    containerRepository.save(c);
                }));
        log.info("Stopped container for project {}", project.getId());
        activityLogService.info(LogCategory.CONTAINER,
                "Container stopped for project: " + project.getName(),
                "ContainerService", project.getId());
    }

    public String getDockerContainerId(Project project) {
        var containerOpt = containerRepository.findByProjectId(project.getId());
        if (containerOpt.isPresent()) {
            Container container = containerOpt.get();
            if (container.getStatus() == ContainerStatus.RUNNING) {
                if (docker.isRunning(container.getDockerContainerId())) {
                    return container.getDockerContainerId();
                }
                // Docker container is gone — correct stale DB status
                log.warn("Container {} for project {} is marked RUNNING but Docker reports it is not running. Updating status.",
                        container.getDockerContainerId(), project.getId());
                container.setStatus(ContainerStatus.STOPPED);
                container.setStoppedAt(Instant.now());
                containerRepository.save(container);
            }
        }
        throw new ContainerException("No running container for project: " + project.getId());
    }

    // NOT @Transactional at the method level: each iteration does a Docker
    // stop+remove (multi-second). The previous implementation wrapped the
    // whole loop in a single transaction, so cleaning up N idle containers
    // held the pool's only connection for N×~5s. We now use short
    // TransactionTemplate bursts per container.
    @Scheduled(fixedRate = 300_000)
    public void cleanupIdleContainers() {
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
                                c.getProject().setStatus(com.automation.entity.ProjectStatus.STOPPED);
                            }
                        }));
                activityLogService.info(LogCategory.CONTAINER,
                        "Idle container cleaned up: " + container.getDockerContainerId(),
                        "ContainerService");
            }
        }
    }
}
