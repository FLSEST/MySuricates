package com.automation.service.project;


import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import com.automation.entity.Project;
import com.automation.exception.ContainerException;
import com.automation.repository.ContainerRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifecycle of project containers (create/reuse, stop) and the containers table.
 * Docker calls go through {@link DockerContainerGateway}; idle cleanup and orphan reconciliation
 * run in {@code service.workflow.IdleContainerReaper}; every DB access is a short
 * {@link TransactionTemplate} burst because the slow Docker work must never hold the single
 * SQLite connection.
 */
@Service
@Slf4j
public class ContainerService {

    private final ContainerRepository containerRepository;
    private final ActivityLogService activityLogService;
    private final PathValidator pathValidator;
    private final DockerContainerGateway docker;
    private final DockerExpectation dockerExpectation;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readOnlyTransactionTemplate;
    private final String hostHome;

    // Per-project lock to serialize concurrent container creation for the same
    // project. Without this, two parallel workflow runs both pass the
    // findByProjectId check, both call Docker create, and the second one
    // clobbers the first container (leaving an orphan Docker container behind).
    //
    // Entries are never removed: dropping a lock while a thread still holds it
    // lets the next caller create a *fresh* monitor for the same project and run
    // in parallel with it — exactly the race this map exists to prevent. The map
    // holds one entry per project id seen since startup (deleted or archived
    // projects included), so it is bounded by that count, not by live projects.
    private final ConcurrentHashMap<String, Object> projectLocks = new ConcurrentHashMap<>();

    public ContainerService(
            ContainerRepository containerRepository,
            ActivityLogService activityLogService,
            PathValidator pathValidator,
            PlatformTransactionManager transactionManager,
            DockerContainerGateway docker,
            DockerExpectation dockerExpectation,
            @Value("${automation.cli.host-home:}") String hostHome) {
        this.containerRepository = containerRepository;
        this.activityLogService = activityLogService;
        this.pathValidator = pathValidator;
        this.docker = docker;
        this.dockerExpectation = dockerExpectation;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
        this.hostHome = hostHome != null && !hostHome.isBlank()
                ? hostHome : System.getProperty("user.home");
    }

    // NOT @Transactional: Docker create/start can take 5-15s. With pool-size=1
    // (SQLite), an enclosing transaction would hold the single connection for
    // the whole Docker call, blocking every other request and causing the
    // browser-side "Failed to fetch" the user reported. Instead we run the
    // Docker work outside any transaction and use TransactionTemplate for the
    // short DB bursts (find existing row / upsert it).
    /** Ob Docker hier eingerichtet ist; ohne Docker versucht ein Run keinen Projekt-Container (a0e85182). */
    public boolean isDockerConfigured() {
        return dockerExpectation.isConfigured();
    }

    public Container createAndStartContainer(Project project) {
        synchronized (projectLocks.computeIfAbsent(project.getId(), k -> new Object())) {
            return doCreateAndStart(project);
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

        String imageName = DockerContainerGateway.imageFor(project.getType());
        String containerName = "automation-" + project.getId();

        // Step 4 (no Tx): clear any leftover Docker container with the same name.
        docker.remove(containerName);

        try {
            // Step 5 (no Tx): create + start the Docker container — the slow
            // operation we are explicitly keeping out of a transaction.
            String dockerContainerId = docker.createAndStart(project, imageName, containerName, hostHome);

            // Step 6 (short Tx): upsert the Container row — reuse the stale row instead of
            // deleting it first. A delete+insert cannot work here: loading the Container
            // eagerly pulls in its Project, whose `container` association cascades PERSIST,
            // so Hibernate un-schedules the deletion again during flush (no DELETE is ever
            // issued). The row survived and the following INSERT hit
            // UNIQUE(containers.project_id). Updating the existing row sidesteps the
            // constraint entirely — there is at most one row per project by definition.
            final String reuseId = oldEntityId;
            Container saved = transactionTemplate.execute(status -> {
                Container container = reuseId != null
                        ? containerRepository.findById(reuseId)
                                .orElseGet(() -> Container.builder().project(project).build())
                        : Container.builder().project(project).build();
                container.setDockerContainerId(dockerContainerId);
                container.setImageName(imageName);
                container.setStatus(ContainerStatus.RUNNING);
                container.setMounts(Map.of("workspace", project.getPath()));
                container.setStartedAt(Instant.now());
                container.setLastActivityAt(Instant.now());
                container.setStoppedAt(null);
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

    // NOT @Transactional: docker.isRunning() is a Docker inspect (network
    // round-trip). Same shape as stopContainer: short read burst, Docker call
    // outside any transaction, short write burst — the single pooled
    // connection must never wait on Docker.
    public String getDockerContainerId(Project project) {
        Container container = readOnlyTransactionTemplate.execute(status ->
                containerRepository.findByProjectId(project.getId()).orElse(null));
        if (container != null && container.getStatus() == ContainerStatus.RUNNING) {
            if (docker.isRunning(container.getDockerContainerId())) {
                return container.getDockerContainerId();
            }
            // Docker container is gone — correct stale DB status
            log.warn("Container {} for project {} is marked RUNNING but Docker reports it is not running. Updating status.",
                    container.getDockerContainerId(), project.getId());
            final String entityId = container.getId();
            transactionTemplate.executeWithoutResult(status ->
                    containerRepository.findById(entityId).ifPresent(c -> {
                        c.setStatus(ContainerStatus.STOPPED);
                        c.setStoppedAt(Instant.now());
                        containerRepository.save(c);
                    }));
        }
        throw new ContainerException("No running container for project: " + project.getId());
    }
}
