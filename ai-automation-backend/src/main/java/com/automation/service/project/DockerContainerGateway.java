package com.automation.service.project;

import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import com.automation.util.HostProcessUtils;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Docker side of project containers: create+start with the hardened host config and mounts,
 * inspect, stop+remove, list the {@code automation-*} containers, and the image per project type.
 * Every call here is slow (seconds) and must run outside any transaction.
 *
 * <p>Extracted from {@code ContainerService} (Audit-TODO b804e1be). Knows nothing about the
 * containers table.
 */
@Component
@Slf4j
public class DockerContainerGateway {

    private final DockerClient dockerClient;
    private final HostProcessUtils hostProcessUtils;
    private final ProjectContainerClaudeAccess claudeAccess;
    private final long cpuQuota;
    private final long memoryLimit;
    private final long pidsLimit;

    public DockerContainerGateway(
            DockerClient dockerClient,
            HostProcessUtils hostProcessUtils,
            ProjectContainerClaudeAccess claudeAccess,
            @Value("${docker.container.cpu-quota}") long cpuQuota,
            @Value("${docker.container.memory-limit}") long memoryLimit,
            @Value("${docker.container.pids-limit}") long pidsLimit) {
        this.dockerClient = dockerClient;
        this.hostProcessUtils = hostProcessUtils;
        this.claudeAccess = claudeAccess;
        this.cpuQuota = cpuQuota;
        this.memoryLimit = memoryLimit;
        this.pidsLimit = pidsLimit;
    }

    /** Creates and starts the project container; returns the Docker container id. */
    public String createAndStart(Project project, String imageName, String containerName, String hostHome) {
        ProjectContainerClaudeAccess.Access claude = claudeAccess.forContainer(hostHome);
        List<Bind> binds = new ArrayList<>();
        binds.add(Bind.parse(project.getPath() + ":/workspace:rw"));
        binds.addAll(claude.binds());
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
                                // Project workspace plus what of Claude comes from the host (f1b6d06b)
                                .withBinds(binds)
                )
                .withEnv(claude.env())
                .withUser(hostProcessUtils.getHostUid())
                .withWorkingDir("/workspace")
                .withCmd("tail", "-f", "/dev/null")
                .exec();

        dockerClient.startContainerCmd(response.getId()).exec();
        return response.getId();
    }

    public boolean isRunning(String containerId) {
        try {
            var info = dockerClient.inspectContainerCmd(containerId).exec();
            return Boolean.TRUE.equals(info.getState().getRunning());
        } catch (Exception e) {
            return false;
        }
    }

    public void remove(String containerId) {
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
        } catch (Exception e) {
            logFailure("stop", containerId, e);
        }
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception e) {
            logFailure("remove", containerId, e);
        }
    }

    /**
     * A container that is already gone (Docker 404, "No such container") or already stopped (Docker
     * 304 on stop, TODO 8ae2d8e0) is what stop/remove wanted anyway — that is DEBUG, not a warning
     * (TODO c1eda062). Every other failure stays WARN.
     */
    private static void logFailure(String action, String containerId, Exception e) {
        if (e instanceof NotFoundException) {
            log.debug("Container {} already gone on {}: {}", containerId, action, e.getMessage());
        } else if (e instanceof NotModifiedException) {
            log.debug("Container {} already stopped on {}: {}", containerId, action, e.getMessage());
        } else {
            log.warn("Failed to {} container {}: {}", action, containerId, e.getMessage());
        }
    }

    /** Running Docker containers whose name starts with {@code automation-}. Propagates Docker errors. */
    public List<com.github.dockerjava.api.model.Container> listAutomationContainers() {
        return dockerClient.listContainersCmd()
                .withNameFilter(List.of("automation-"))
                .exec();
    }

    public static String imageFor(ProjectType type) {
        return switch (type) {
            case CODE, PERSONAL -> "automation-agent-node:latest";
            case RESEARCH, CONTENT, BUSINESS -> "automation-agent-python:latest";
        };
    }
}
