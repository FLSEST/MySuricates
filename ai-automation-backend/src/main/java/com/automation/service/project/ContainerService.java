package com.automation.service.project;

import com.automation.entity.Container;
import com.automation.entity.ContainerStatus;
import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import com.automation.exception.ContainerException;
import com.automation.repository.ContainerRepository;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ContainerService {

    private final DockerClient dockerClient;
    private final ContainerRepository containerRepository;
    private final long cpuQuota;
    private final long memoryLimit;
    private final long pidsLimit;
    private final int idleTimeoutMinutes;

    public ContainerService(
            DockerClient dockerClient,
            ContainerRepository containerRepository,
            @Value("${docker.container.cpu-quota}") long cpuQuota,
            @Value("${docker.container.memory-limit}") long memoryLimit,
            @Value("${docker.container.pids-limit}") long pidsLimit,
            @Value("${docker.container.idle-timeout-minutes}") int idleTimeoutMinutes) {
        this.dockerClient = dockerClient;
        this.containerRepository = containerRepository;
        this.cpuQuota = cpuQuota;
        this.memoryLimit = memoryLimit;
        this.pidsLimit = pidsLimit;
        this.idleTimeoutMinutes = idleTimeoutMinutes;
    }

    @Transactional
    public Container createAndStartContainer(Project project) {
        // Check if container already exists
        var existing = containerRepository.findByProjectId(project.getId());
        if (existing.isPresent()) {
            Container container = existing.get();
            if (container.getStatus() == ContainerStatus.RUNNING) {
                return container;
            }
            // Remove old container
            removeDockerContainer(container.getDockerContainerId());
            containerRepository.delete(container);
        }

        String imageName = getImageForType(project.getType());
        String containerName = "automation-" + project.getId();

        try {
            CreateContainerResponse response = dockerClient.createContainerCmd(imageName)
                    .withName(containerName)
                    .withHostConfig(
                            HostConfig.newHostConfig()
                                    .withReadonlyRootfs(true)
                                    .withTmpFs(Map.of("/tmp", "", "/home/aiuser/.cache", ""))
                                    .withCapDrop(com.github.dockerjava.api.model.Capability.ALL)
                                    .withSecurityOpts(List.of("no-new-privileges:true"))
                                    .withCpuQuota(cpuQuota)
                                    .withMemory(memoryLimit)
                                    .withPidsLimit(pidsLimit)
                                    .withBinds(Bind.parse(project.getPath() + ":/workspace:rw"))
                    )
                    .withUser("aiuser")
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
                    .build();

            Container saved = containerRepository.save(container);
            project.setContainer(saved);

            log.info("Started container {} for project {}", response.getId(), project.getId());
            return saved;

        } catch (Exception e) {
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
        });
    }

    public String getDockerContainerId(Project project) {
        return containerRepository.findByProjectId(project.getId())
                .filter(c -> c.getStatus() == ContainerStatus.RUNNING)
                .map(Container::getDockerContainerId)
                .orElseThrow(() -> new ContainerException(
                        "No running container for project: " + project.getId()));
    }

    @Scheduled(fixedRate = 300_000)
    @Transactional
    public void cleanupIdleContainers() {
        List<Container> running = containerRepository.findByStatus(ContainerStatus.RUNNING);
        Instant threshold = Instant.now().minus(Duration.ofMinutes(idleTimeoutMinutes));

        for (Container container : running) {
            if (container.getStartedAt() != null && container.getStartedAt().isBefore(threshold)) {
                log.info("Stopping idle container: {}", container.getDockerContainerId());
                removeDockerContainer(container.getDockerContainerId());
                container.setStatus(ContainerStatus.STOPPED);
                container.setStoppedAt(Instant.now());
                containerRepository.save(container);

                if (container.getProject() != null) {
                    container.getProject().setStatus(com.automation.entity.ProjectStatus.STOPPED);
                }
            }
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
