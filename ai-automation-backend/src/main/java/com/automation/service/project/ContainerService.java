package com.automation.service.project;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import com.automation.exception.ContainerException;
import com.automation.repository.ContainerRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.util.HostProcessUtils;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
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

@Service
@Slf4j
public class ContainerService {

    private final DockerClient dockerClient;
    private final ContainerRepository containerRepository;
    private final WorkflowExecutor workflowExecutor;
    private final ActivityLogService activityLogService;
    private final HostProcessUtils hostProcessUtils;
    private final PathValidator pathValidator;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readOnlyTransactionTemplate;
    private static final String CONTAINER_HOME = "/home/agent";

    private final long cpuQuota;
    private final long memoryLimit;
    private final long pidsLimit;
    private final int idleTimeoutMinutes;
    private final String hostHome;

    // Per-project lock to serialize concurrent container creation for the same
    // project. Without this, two parallel workflow runs both pass the
    // findByProjectId check, both call Docker create, and the second INSERT
    // fails with UNIQUE constraint on containers.project_id (leaving an
    // orphan Docker container behind).
    private final ConcurrentHashMap<String, Object> projectLocks = new ConcurrentHashMap<>();

    public ContainerService(
            DockerClient dockerClient,
            ContainerRepository containerRepository,
            @Lazy WorkflowExecutor workflowExecutor,
            ActivityLogService activityLogService,
            HostProcessUtils hostProcessUtils,
            PathValidator pathValidator,
            PlatformTransactionManager transactionManager,
            @Value("${docker.container.cpu-quota}") long cpuQuota,
            @Value("${docker.container.memory-limit}") long memoryLimit,
            @Value("${docker.container.pids-limit}") long pidsLimit,
            @Value("${docker.container.idle-timeout-minutes}") int idleTimeoutMinutes,
            @Value("${automation.cli.host-home:}") String hostHome) {
        this.dockerClient = dockerClient;
        this.containerRepository = containerRepository;
        this.workflowExecutor = workflowExecutor;
        this.activityLogService = activityLogService;
        this.hostProcessUtils = hostProcessUtils;
        this.pathValidator = pathValidator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
        this.cpuQuota = cpuQuota;
        this.memoryLimit = memoryLimit;
        this.pidsLimit = pidsLimit;
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
        // for cleanup. isDockerContainerRunning() is a Docker call (slow).
        String oldDockerContainerId = null;
        String oldEntityId = null;
        if (existing != null) {
            if (existing.getStatus() == ContainerStatus.RUNNING
                    && isDockerContainerRunning(existing.getDockerContainerId())) {
                return existing;
            }
            oldDockerContainerId = existing.getDockerContainerId();
            oldEntityId = existing.getId();
        }

        // Step 3 (no Tx): stop & remove the old Docker container — slow.
        if (oldDockerContainerId != null) {
            removeDockerContainer(oldDockerContainerId);
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

        String imageName = getImageForType(project.getType());
        String containerName = "automation-" + project.getId();

        // Step 5 (no Tx): clear any leftover Docker container with the same name.
        removeDockerContainer(containerName);

        try {
            // Step 6 (no Tx): create + start the Docker container — the slow
            // operation we are explicitly keeping out of a transaction.
            CreateContainerResponse response = dockerClient.createContainerCmd(imageName)
                    .withName(containerName)
                    .withHostConfig(
                            HostConfig.newHostConfig()
                                    .withTmpFs(Map.of("/tmp", ""))
                                    .withCapDrop(com.github.dockerjava.api.model.Capability.ALL)
                                    .withSecurityOpts(List.of("no-new-privileges:true"))
                                    .withCpuQuota(cpuQuota)
                                    .withMemory(memoryLimit)
                                    .withPidsLimit(pidsLimit)
                                    .withBinds(
                                            // Project workspace
                                            Bind.parse(project.getPath() + ":/workspace:rw"),
                                            // Claude binary (read-only, from host)
                                            Bind.parse(hostHome + "/.local/bin/claude:" + CONTAINER_HOME + "/.local/bin/claude:ro"),
                                            Bind.parse(hostHome + "/.local/share/claude:" + CONTAINER_HOME + "/.local/share/claude:ro"),
                                            // Claude config (read-write, CLI needs to write session state/todos)
                                            // TODO: Consider copying only auth credentials read-only and letting CLI manage its own state
                                            Bind.parse(hostHome + "/.claude:" + CONTAINER_HOME + "/.claude"),
                                            Bind.parse(hostHome + "/.claude.json:" + CONTAINER_HOME + "/.claude.json")
                                    )
                    )
                    .withUser(hostProcessUtils.getHostUid())
                    .withWorkingDir("/workspace")
                    .withCmd("tail", "-f", "/dev/null")
                    .exec();

            dockerClient.startContainerCmd(response.getId()).exec();

            // Step 7 (short Tx): persist the new Container row.
            Container saved = transactionTemplate.execute(status -> {
                Container container = Container.builder()
                        .project(project)
                        .dockerContainerId(response.getId())
                        .imageName(imageName)
                        .status(ContainerStatus.RUNNING)
                        .mounts(Map.of("workspace", project.getPath()))
                        .startedAt(Instant.now())
                        .lastActivityAt(Instant.now())
                        .build();
                return containerRepository.save(container);
            });
            project.setContainer(saved);

            log.info("Started container {} for project {}", response.getId(), project.getId());
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

    // NOT @Transactional: removeDockerContainer() does Docker stop+remove
    // (multi-second). See createAndStartContainer for the rationale.
    public void stopContainer(Project project) {
        Container container = readOnlyTransactionTemplate.execute(status ->
                containerRepository.findByProjectId(project.getId()).orElse(null));
        if (container == null) return;

        removeDockerContainer(container.getDockerContainerId());

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
                if (isDockerContainerRunning(container.getDockerContainerId())) {
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
        Set<String> activeContainers = workflowExecutor.getActiveContainerIds();

        reconcileDockerOrphans(running, activeContainers);
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
                removeDockerContainer(container.getDockerContainerId());

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

    /**
     * State-drift reconciliation: Docker containers named automation-* that are RUNNING
     * in Docker but have no RUNNING row in the containers table are invisible to the
     * DB-driven idle cleanup and would live forever. Such orphans are force-stopped;
     * they restart lazily on the next container workflow (~5-15s cold start).
     * A grace period (idleTimeoutMinutes, based on Docker's created timestamp) protects
     * containers whose DB row is still being written.
     */
    private void reconcileDockerOrphans(List<Container> runningRows, Set<String> activeContainers) {
        List<com.github.dockerjava.api.model.Container> dockerRunning;
        try {
            dockerRunning = dockerClient.listContainersCmd()
                    .withNameFilter(List.of("automation-"))
                    .exec();
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

        for (var docker : dockerRunning) {
            String id = docker.getId();
            if (id == null || knownRunningIds.contains(id) || activeContainers.contains(id)) continue;
            if (docker.getCreated() != null && Instant.ofEpochSecond(docker.getCreated()).isAfter(graceCutoff)) {
                continue; // startup race: DB row may not be committed yet
            }
            log.warn("Container reconcile: orphaned Docker container {} has no RUNNING DB row — force-stopping", id);
            removeDockerContainer(id);
            activityLogService.warn(LogCategory.CONTAINER,
                    "Reconcile: verwaister Docker-Container ohne DB-Eintrag gestoppt: " + id,
                    "ContainerService");
        }
    }

    private boolean isDockerContainerRunning(String containerId) {
        try {
            var info = dockerClient.inspectContainerCmd(containerId).exec();
            return Boolean.TRUE.equals(info.getState().getRunning());
        } catch (Exception e) {
            return false;
        }
    }

    private void removeDockerContainer(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
        } catch (Exception e) {
            log.warn("Failed to stop container {}: {}", containerId, e.getMessage());
        }
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception e) {
            log.warn("Failed to remove container {}: {}", containerId, e.getMessage());
        }
    }

    private String getImageForType(ProjectType type) {
        return switch (type) {
            case CODE, PERSONAL -> "automation-agent-node:latest";
            case RESEARCH, CONTENT, BUSINESS -> "automation-agent-python:latest";
        };
    }

}
