package com.automation.service.containerimage;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerImageInspectorTest {

    private final DockerClient dockerClient = mock(DockerClient.class);
    private final InspectImageCmd command = mock(InspectImageCmd.class);
    private final InspectImageResponse response = mock(InspectImageResponse.class);
    private final ContainerImageInspector inspector = new ContainerImageInspector(dockerClient);

    @BeforeEach
    void setUp() {
        when(dockerClient.inspectImageCmd("agent:latest")).thenReturn(command);
    }

    @Test
    void inspect_returnsBuildDateAndLabels() {
        ContainerConfig config = mock(ContainerConfig.class);
        when(config.getLabels()).thenReturn(Map.of("org.mysuricates.dockerfile-sha256", "abc"));
        when(response.getConfig()).thenReturn(config);
        when(response.getCreated()).thenReturn("2026-10-01T12:00:00Z");
        when(command.exec()).thenReturn(response);

        assertThat(inspector.inspect("agent:latest")).contains(new ContainerImageInspector.ImageFacts(
                "2026-10-01T12:00:00Z", Map.of("org.mysuricates.dockerfile-sha256", "abc")));
    }

    @Test
    void inspect_givesEmptyLabels_when_imageHasNoConfigOrNoLabels() {
        when(response.getCreated()).thenReturn("c");
        when(command.exec()).thenReturn(response);

        when(response.getConfig()).thenReturn(null);
        assertThat(inspector.inspect("agent:latest")).get()
                .extracting(ContainerImageInspector.ImageFacts::labels).isEqualTo(Map.of());

        ContainerConfig config = mock(ContainerConfig.class);
        when(config.getLabels()).thenReturn(null);
        when(response.getConfig()).thenReturn(config);
        assertThat(inspector.inspect("agent:latest")).get()
                .extracting(ContainerImageInspector.ImageFacts::labels).isEqualTo(Map.of());
    }

    @Test
    void inspect_isEmpty_when_imageDoesNotExistLocally() {
        when(command.exec()).thenThrow(new NotFoundException("No such image"));

        assertThat(inspector.inspect("agent:latest")).isEmpty();
    }

    @Test
    void inspect_propagatesOtherDockerErrors() {
        when(command.exec()).thenThrow(new DockerException("daemon down", 500));

        assertThatThrownBy(() -> inspector.inspect("agent:latest"))
                .isInstanceOf(DockerException.class)
                .hasMessageContaining("daemon down");
    }
}
