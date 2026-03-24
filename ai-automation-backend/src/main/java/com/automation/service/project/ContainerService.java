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
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class ContainerService {

    private final DockerClient dockerClient;
    private final ContainerRepository containerRepository;
    private final WorkflowExecutor workflowExecutor;
    private final ActivityLogService activityLogService;
    private final HostProcessUtils hostProcessUtils;
    private static final String CONTAINER_HOME = "/home/agent";

    private final long cpuQuota;
    private final long memoryLimit;
    private final long pidsLimit;
    private final int idleTimeoutMinutes;
    private final String hostHome;

    public ContainerService(
            DockerClient dockerClient,
            ContainerRepository containerRepository,
            @Lazy WorkflowExecutor workflowExecutor,
            ActivityLogService activityLogService,
            HostProcessUtils hostProcessUtils,
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
        this.cpuQuota = cpuQuota;
        this.memoryLimit = memoryLimit;
        this.pidsLimit = pidsLimit;
        this.idleTimeoutMinutes = idleTimeoutMinutes;
        this.hostHome = hostHome != null && !hostHome.isBlank()
                ? hostHome : System.getProperty("user.home");
    }

    @Transactional
    public Container createAndStartContainer(Project project) {
        // Check if container already exists
        var existing = containerRepository.findByProjectId(project.getId());
        if (existing.isPresent()) {
            Container container = existing.get();
            if (container.getStatus() == ContainerStatus.RUNNING && isDockerContainerRunning(container.getDockerContainerId())) {
                return container;
            }
            // Remove old/stale container
            removeDockerContainer(container.getDockerContainerId());
            containerRepository.delete(container);
            containerRepository.flush();
        }

        String imageName = getImageForType(project.getType());
        String containerName = "automation-" + project.getId();

        // Remove any leftover Docker container with the same name
        removeDockerContainer(containerName);

        try {
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

            Container container = Container.builder()
                    .project(project)
                    .dockerContainerId(response.getId())
                    .imageName(imageName)
                    .status(ContainerStatus.RUNNING)
                    .mounts(Map.of("workspace", project.getPath()))
                    .startedAt(Instant.now())
                    .lastActivityAt(Instant.now())
                    .build();

            Container saved = containerRepository.save(container);
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

    @Transactional
    public void stopContainer(Project project) {
        containerRepository.findByProjectId(project.getId()).ifPresent(container -> {
            removeDockerContainer(container.getDockerContainerId());
            container.setStatus(ContainerStatus.STOPPED);
            container.setStoppedAt(Instant.now());
            containerRepository.save(container);
            log.info("Stopped container for project {}", project.getId());
            activityLogService.info(LogCategory.CONTAINER,
                    "Container stopped for project: " + project.getName(),
                    "ContainerService", project.getId());
        });
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

    @Scheduled(fixedRate = 300_000)
    @Transactional
    public void cleanupIdleContainers() {
        List<Container> running = containerRepository.findByStatus(ContainerStatus.RUNNING);
        Instant threshold = Instant.now().minus(Duration.ofMinutes(idleTimeoutMinutes));
        Set<String> activeContainers = workflowExecutor.getActiveContainerIds();

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
                container.setStatus(ContainerStatus.STOPPED);
                container.setStoppedAt(Instant.now());
                containerRepository.save(container);
                activityLogService.info(LogCategory.CONTAINER,
                        "Idle container cleaned up: " + container.getDockerContainerId(),
                        "ContainerService");

                if (container.getProject() != null) {
                    container.getProject().setStatus(com.automation.entity.ProjectStatus.STOPPED);
                }
            }
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
