package com.automation.service.workflow;

import com.automation.entity.Container;
import com.automation.entity.Project;
import com.automation.service.project.ContainerService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RunContainerPreparationTest {

    private final ContainerService containers = mock(ContainerService.class);
    private final ExecutionBroadcaster broadcaster = mock(ExecutionBroadcaster.class);
    private final Project project = Project.builder().id("p").name("Demo").build();

    /** Alles ausser dem ersten Test setzt eingerichtetes Docker voraus — sonst kehrt der Aufruf sofort um. */
    private void dockerConfigured() {
        when(containers.isDockerConfigured()).thenReturn(true);
    }

    @Test
    void withoutDocker_nothingIsAttempted_andNothingIsBroadcast() {
        // a0e85182: ohne eingerichtetes Docker kein Versuch — sonst je Run ein Activity-Log-ERROR
        when(containers.isDockerConfigured()).thenReturn(false);

        assertThat(RunContainerPreparation.ensureRunning(project, "run", containers, broadcaster)).isNull();

        verify(containers, never()).createAndStartContainer(any());
        verify(containers, never()).getDockerContainerId(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void runningContainer_isReusedWithoutBroadcast() {
        dockerConfigured();
        when(containers.getDockerContainerId(project)).thenReturn("docker-1");

        assertThat(RunContainerPreparation.ensureRunning(project, "run", containers, broadcaster)).isEqualTo("docker-1");

        verify(containers, never()).createAndStartContainer(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void missingContainer_isStarted_andAnnounced() {
        dockerConfigured();
        when(containers.getDockerContainerId(project)).thenThrow(new IllegalStateException("none"));
        when(containers.createAndStartContainer(project))
                .thenReturn(Container.builder().dockerContainerId("docker-2").build());

        assertThat(RunContainerPreparation.ensureRunning(project, "run", containers, broadcaster)).isEqualTo("docker-2");

        verify(broadcaster).broadcastLogMessage(eq("run"), eq("container_starting"), anyMap());
        verify(broadcaster).broadcastLogMessage("run", "container_started",
                Map.of("message", "Container gestartet", "containerId", "docker-2"));
    }

    @Test
    void failedStart_fallsBackToHostExecution_withNullContainer() {
        dockerConfigured();
        when(containers.getDockerContainerId(project)).thenThrow(new IllegalStateException("none"));
        when(containers.createAndStartContainer(project)).thenThrow(new RuntimeException("docker down"));

        assertThat(RunContainerPreparation.ensureRunning(project, "run", containers, broadcaster)).isNull();

        verify(broadcaster).broadcastLogMessage(eq("run"), eq("container_skipped"), anyMap());
        verify(broadcaster, never()).broadcastLogMessage(anyString(), eq("container_started"), anyMap());
    }
}
