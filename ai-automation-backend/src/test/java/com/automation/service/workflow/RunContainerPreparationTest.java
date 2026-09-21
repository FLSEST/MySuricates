package com.automation.service.workflow;

import com.automation.entity.Project;
import com.automation.service.project.ContainerService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RunContainerPreparationTest {

    @Test
    void withoutDockerNoContainerIsAttemptedAndNothingIsBroadcast() {
        // a0e85182: ohne eingerichtetes Docker kein Versuch — sonst je Run ein Activity-Log-ERROR
        ContainerService containers = mock(ContainerService.class);
        ExecutionBroadcaster broadcaster = mock(ExecutionBroadcaster.class);
        when(containers.isDockerConfigured()).thenReturn(false);

        String id = RunContainerPreparation.ensureRunning(new Project(), "run-1", containers, broadcaster);

        assertThat(id).isNull();
        verify(containers, never()).createAndStartContainer(any());
        verify(containers, never()).getDockerContainerId(any());
        verifyNoInteractions(broadcaster);
    }

    @Test
    void withDockerARunningContainerIsReused() {
        ContainerService containers = mock(ContainerService.class);
        ExecutionBroadcaster broadcaster = mock(ExecutionBroadcaster.class);
        when(containers.isDockerConfigured()).thenReturn(true);
        when(containers.getDockerContainerId(any())).thenReturn("abc123");

        assertThat(RunContainerPreparation.ensureRunning(new Project(), "run-1", containers, broadcaster)).isEqualTo("abc123");
        verify(containers, never()).createAndStartContainer(any());
    }
}
