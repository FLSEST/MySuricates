package com.automation.service.project;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import com.automation.util.HostProcessUtils;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Locks the Docker-facing behaviour before extraction (Audit-TODO b804e1be). */
@ExtendWith(MockitoExtension.class)
class DockerContainerGatewayTest {

    @Mock private DockerClient dockerClient;
    @Mock private HostProcessUtils hostProcessUtils;
    @Mock private ProjectContainerClaudeAccess claudeAccess;

    private DockerContainerGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new DockerContainerGateway(dockerClient, hostProcessUtils, claudeAccess, 100_000L, 4_294_967_296L, 100L);
    }

    @Test
    void imageFor_mapsProjectTypes() {
        assertThat(DockerContainerGateway.imageFor(ProjectType.CODE)).isEqualTo("automation-agent-node:latest");
        assertThat(DockerContainerGateway.imageFor(ProjectType.PERSONAL)).isEqualTo("automation-agent-node:latest");
        assertThat(DockerContainerGateway.imageFor(ProjectType.RESEARCH)).isEqualTo("automation-agent-python:latest");
        assertThat(DockerContainerGateway.imageFor(ProjectType.CONTENT)).isEqualTo("automation-agent-python:latest");
        assertThat(DockerContainerGateway.imageFor(ProjectType.BUSINESS)).isEqualTo("automation-agent-python:latest");
    }

    @Test
    void isRunning_trueWhenDockerReportsRunning_falseOtherwiseOrOnError() {
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(state.getRunning()).thenReturn(true, false);
        when(response.getState()).thenReturn(state);
        InspectContainerCmd cmd = mock(InspectContainerCmd.class);
        when(cmd.exec()).thenReturn(response);
        when(dockerClient.inspectContainerCmd("c1")).thenReturn(cmd);
        InspectContainerCmd failing = mock(InspectContainerCmd.class);
        when(failing.exec()).thenThrow(new RuntimeException("gone"));
        when(dockerClient.inspectContainerCmd("c2")).thenReturn(failing);

        assertThat(gateway.isRunning("c1")).isTrue();
        assertThat(gateway.isRunning("c1")).isFalse();
        assertThat(gateway.isRunning("c2")).isFalse();
    }

    @Test
    void remove_stopsWithTimeoutThenForceRemoves_andSwallowsFailures() {
        StopContainerCmd stop = mock(StopContainerCmd.class);
        when(stop.withTimeout(anyInt())).thenReturn(stop);
        when(stop.exec()).thenThrow(new RuntimeException("already stopped"));
        when(dockerClient.stopContainerCmd("c1")).thenReturn(stop);
        RemoveContainerCmd remove = mock(RemoveContainerCmd.class);
        when(remove.withForce(true)).thenReturn(remove);
        when(dockerClient.removeContainerCmd("c1")).thenReturn(remove);

        assertThatCode(() -> gateway.remove("c1")).doesNotThrowAnyException();

        verify(stop).withTimeout(10);
        verify(remove).exec();
    }

    /** c1eda062: a container Docker no longer knows (404) is logged at DEBUG; other failures stay WARN. */
    @Test
    void remove_logsAMissingContainerAtDebug_andOtherFailuresAtWarn() {
        Logger logger = (Logger) LoggerFactory.getLogger(DockerContainerGateway.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            StopContainerCmd stop = mock(StopContainerCmd.class);
            when(stop.withTimeout(anyInt())).thenReturn(stop);
            when(stop.exec()).thenThrow(new NotFoundException("No such container: c1"));
            when(dockerClient.stopContainerCmd("c1")).thenReturn(stop);
            RemoveContainerCmd remove = mock(RemoveContainerCmd.class);
            when(remove.withForce(true)).thenReturn(remove);
            when(remove.exec()).thenThrow(new RuntimeException("Status 500: driver failed"));
            when(dockerClient.removeContainerCmd("c1")).thenReturn(remove);

            gateway.remove("c1");

            assertThat(appender.list).extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                    .containsExactly(
                            tuple(Level.DEBUG, "Container c1 already gone on stop: Status 404: No such container: c1"),
                            tuple(Level.WARN, "Failed to remove container c1: Status 500: driver failed"));
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
        }
    }

    @Test
    void createAndStart_buildsNamedContainer_startsIt_andReturnsId() {
        when(hostProcessUtils.getHostUid()).thenReturn("1000");
        CreateContainerResponse created = mock(CreateContainerResponse.class);
        when(created.getId()).thenReturn("docker-123");
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_DEEP_STUBS);
        when(createCmd.withName(anyString())).thenReturn(createCmd);
        when(createCmd.withHostConfig(any())).thenReturn(createCmd);
        when(createCmd.withUser(anyString())).thenReturn(createCmd);
        when(createCmd.withEnv(anyList())).thenReturn(createCmd);
        when(claudeAccess.forContainer("/home/test")).thenReturn(new ProjectContainerClaudeAccess.Access(
                List.of(Bind.parse("/home/test/.local/bin/claude:/home/agent/.local/bin/claude:ro")),
                List.of("CLAUDE_CONFIG_DIR=/tmp/claude-config")));
        when(createCmd.withWorkingDir(anyString())).thenReturn(createCmd);
        when(createCmd.withCmd(any(String[].class))).thenReturn(createCmd);
        when(createCmd.exec()).thenReturn(created);
        when(dockerClient.createContainerCmd("automation-agent-node:latest")).thenReturn(createCmd);
        StartContainerCmd start = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("docker-123")).thenReturn(start);
        Project project = Project.builder().id("p1").path("/work/p1").type(ProjectType.CODE).build();

        String id = gateway.createAndStart(project, "automation-agent-node:latest", "automation-p1", "/home/test");

        assertThat(id).isEqualTo("docker-123");
        verify(createCmd).withName("automation-p1");
        verify(createCmd).withUser("1000");
        verify(createCmd).withWorkingDir("/workspace");
        verify(createCmd).withCmd("tail", "-f", "/dev/null");
        verify(start).exec();
    }

    /** Workspace plus exactly what the Claude rule hands over — never ~/.claude of the host (f1b6d06b). */
    @Test
    void createAndStart_mountsTheWorkspaceAndOnlyWhatTheClaudeRuleGives() {
        when(hostProcessUtils.getHostUid()).thenReturn("1000");
        CreateContainerResponse created = mock(CreateContainerResponse.class);
        when(created.getId()).thenReturn("docker-9");
        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, RETURNS_DEEP_STUBS);
        when(createCmd.withName(anyString())).thenReturn(createCmd);
        when(createCmd.withHostConfig(any())).thenReturn(createCmd);
        when(createCmd.withEnv(anyList())).thenReturn(createCmd);
        when(createCmd.withUser(anyString())).thenReturn(createCmd);
        when(createCmd.withWorkingDir(anyString())).thenReturn(createCmd);
        when(createCmd.withCmd(any(String[].class))).thenReturn(createCmd);
        when(createCmd.exec()).thenReturn(created);
        when(dockerClient.createContainerCmd(anyString())).thenReturn(createCmd);
        when(dockerClient.startContainerCmd("docker-9")).thenReturn(mock(StartContainerCmd.class));
        when(claudeAccess.forContainer("/home/test")).thenReturn(new ProjectContainerClaudeAccess.Access(
                List.of(Bind.parse("/home/test/.local/bin/claude:/home/agent/.local/bin/claude:ro")),
                List.of("CLAUDE_CONFIG_DIR=/tmp/claude-config", "DISABLE_AUTOUPDATER=1")));
        Project project = Project.builder().id("p9").path("/work/p9").type(ProjectType.CODE).build();

        gateway.createAndStart(project, "automation-agent-node:latest", "automation-p9", "/home/test");

        ArgumentCaptor<HostConfig> hostConfig = ArgumentCaptor.forClass(HostConfig.class);
        verify(createCmd).withHostConfig(hostConfig.capture());
        assertThat(hostConfig.getValue().getBinds()).extracting(Bind::toString).containsExactly(
                "/work/p9:/workspace:rw", "/home/test/.local/bin/claude:/home/agent/.local/bin/claude:ro");
        verify(createCmd).withEnv(List.of("CLAUDE_CONFIG_DIR=/tmp/claude-config", "DISABLE_AUTOUPDATER=1"));
    }

    @Test
    void listAutomationContainers_filtersByNamePrefix() {
        ListContainersCmd list = mock(ListContainersCmd.class);
        when(list.withNameFilter(List.of("automation-"))).thenReturn(list);
        when(list.exec()).thenReturn(List.of());
        when(dockerClient.listContainersCmd()).thenReturn(list);

        assertThat(gateway.listAutomationContainers()).isEmpty();
        verify(list).withNameFilter(List.of("automation-"));
    }
}
