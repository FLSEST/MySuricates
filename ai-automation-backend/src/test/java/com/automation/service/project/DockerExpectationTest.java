package com.automation.service.project;

import com.automation.service.project.DockerExpectation.State;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PingCmd;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DockerExpectationTest {

    private final DockerClient client = mock(DockerClient.class);

    @Test
    void unixSocketThatExistsIsConfigured(@TempDir Path dir) throws Exception {
        Path sock = Files.createFile(dir.resolve("docker.sock"));
        assertThat(new DockerExpectation(client, "unix://" + sock).isConfigured()).isTrue();
    }

    @Test
    void missingUnixSocketIsNotConfigured(@TempDir Path dir) {
        assertThat(new DockerExpectation(client, "unix://" + dir.resolve("nope.sock")).isConfigured()).isFalse();
    }

    @Test
    void tcpHostIsAlwaysConfigured() {
        assertThat(new DockerExpectation(client, "tcp://10.0.0.5:2375").isConfigured()).isTrue();
    }

    @Test
    void blankHostIsNotConfigured() {
        assertThat(new DockerExpectation(client, " ").isConfigured()).isFalse();
    }

    @Test
    void probeIsUpWhenPingAnswers() {
        when(client.pingCmd()).thenReturn(mock(PingCmd.class));
        assertThat(new DockerExpectation(client, "tcp://x:2375").probe()).isEqualTo(State.UP);
    }

    @Test
    void failedPingIsDownOnlyWhenDockerIsConfigured(@TempDir Path dir) {
        when(client.pingCmd()).thenThrow(new RuntimeException("refused"));
        assertThat(new DockerExpectation(client, "tcp://x:2375").probe()).isEqualTo(State.DOWN);
        assertThat(new DockerExpectation(client, "unix://" + dir.resolve("none.sock")).probe()).isEqualTo(State.NOT_CONFIGURED);
    }
}
