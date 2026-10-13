package com.automation.service.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DockerAccessTest {

    private static DockerAccess access(DockerExpectation.State state, Map<String, String> env, boolean marker) {
        DockerExpectation expectation = mock(DockerExpectation.class);
        when(expectation.probe()).thenReturn(state);
        return new DockerAccess(expectation, env::get, () -> marker);
    }

    @Test
    @DisplayName("a responding Docker needs no hint")
    void reachable() {
        DockerAccess.Status status = access(DockerExpectation.State.UP, Map.of(), false).status();

        assertThat(status.reachable()).isTrue();
        assertThat(status.hint()).isEmpty();
        assertThat(status.hintEn()).isEmpty();
    }

    @Test
    @DisplayName("an instance in a container names its socket switch and where it is set")
    void containerWithSwitch() {
        DockerAccess.Status status = access(DockerExpectation.State.NOT_CONFIGURED, Map.of(
                "MYSURICATES_RUNTIME", "container",
                "MYSURICATES_SWITCH_PREFIX", "TEST_",
                "MYSURICATES_SWITCH_SOURCE", "/home/user/.config/mysuricates/staging.env"), false).status();

        assertThat(status.reachable()).isFalse();
        assertThat(status.inContainer()).isTrue();
        assertThat(status.socketSwitch()).isEqualTo("TEST_DOCKER_SOCKET");
        assertThat(status.hintEn())
                .contains("container without the Docker socket")
                .contains("TEST_DOCKER_SOCKET=1 in /home/user/.config/mysuricates/staging.env")
                .contains("root-equivalent");
        assertThat(status.hint()).contains("TEST_DOCKER_SOCKET=1 in /home/user/.config/mysuricates/staging.env");
    }

    @Test
    @DisplayName("deploy/ sets no runtime variable, the marker file tells it is a container")
    void containerByMarkerFile() {
        DockerAccess.Status status = access(DockerExpectation.State.NOT_CONFIGURED, Map.of(), true).status();

        assertThat(status.inContainer()).isTrue();
        assertThat(status.socketSwitch()).isNull();
        assertThat(status.hintEn()).contains("restarted with the socket mounted").doesNotContain("install");
    }

    @Test
    @DisplayName("the tryout names the real reason instead of a switch, even if switch variables leak in")
    void tryoutHasNoSwitch() {
        DockerAccess.Status status = access(DockerExpectation.State.NOT_CONFIGURED, Map.of(
                "MYSURICATES_RUNTIME", "tryout",
                "MYSURICATES_SWITCH_PREFIX", "TEST_",
                "MYSURICATES_SWITCH_SOURCE", "/somewhere/staging.env"), false).status();

        assertThat(status.inContainer()).isTrue();
        assertThat(status.tryout()).isTrue();
        assertThat(status.socketSwitch()).isNull();
        assertThat(status.switchSource()).isNull();
        assertThat(status.hintEn()).contains("Docker volume").contains("scripts/instance.sh")
                .doesNotContain("restarted with the socket");
        assertThat(status.hint()).contains("Docker-Volume").contains("scripts/setup.sh");
    }

    @Test
    @DisplayName("a responding Docker in the tryout needs no hint either")
    void tryoutReachable() {
        DockerAccess.Status status = access(DockerExpectation.State.UP,
                Map.of("MYSURICATES_RUNTIME", "tryout"), true).status();

        assertThat(status.hint()).isEmpty();
    }

    @Test
    @DisplayName("the runtime variable wins over the marker file")
    void runtimeVariableWins() {
        DockerAccess.Status status = access(DockerExpectation.State.NOT_CONFIGURED,
                Map.of("MYSURICATES_RUNTIME", "host"), true).status();

        assertThat(status.inContainer()).isFalse();
        assertThat(status.hintEn()).isEqualTo("Docker is not set up on this machine - install and start Docker.");
    }

    @Test
    @DisplayName("switch names stay out of a host instance even if the variables are set")
    void hostIgnoresSwitchVariables() {
        DockerAccess.Status status = access(DockerExpectation.State.DOWN, Map.of(
                "MYSURICATES_RUNTIME", "host", "MYSURICATES_SWITCH_PREFIX", "TEST_"), false).status();

        assertThat(status.socketSwitch()).isNull();
        assertThat(status.hintEn()).contains("does not respond");
        assertThat(status.hint()).contains("antwortet aber nicht");
    }
}
