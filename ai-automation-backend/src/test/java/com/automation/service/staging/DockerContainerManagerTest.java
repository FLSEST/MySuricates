package com.automation.service.staging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DockerContainerManagerTest {

    private DockerContainerManager manager;

    @BeforeEach
    void setUp() {
        manager = new DockerContainerManager();
    }

    @Test
    void isRunning_existingContainer_noException() {
        // Will return false in test env (no such container), but should not throw
        boolean result = manager.isRunning("nonexistent-test-container-12345");
        assertThat(result).isFalse();
    }

    @Test
    void isHealthy_invalidUrl_returnsFalse() {
        boolean result = manager.isHealthy("http://localhost:99999/health");
        assertThat(result).isFalse();
    }

    @Test
    void isHealthy_unreachableHost_returnsFalse() {
        boolean result = manager.isHealthy("http://192.0.2.1:1/health"); // RFC 5737 TEST-NET
        assertThat(result).isFalse();
    }

    @Test
    void runCommand_successfulCommand_noException() {
        manager.runCommand("test", Path.of("/tmp"), "echo", "hello");
        // No exception = success
    }

    @Test
    void runCommand_nonZeroExit_throwsRuntimeException() {
        assertThatThrownBy(() ->
                manager.runCommand("test", Path.of("/tmp"), "false"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("exit");
    }

    @Test
    void runCommand_invalidCommand_throwsException() {
        assertThatThrownBy(() ->
                manager.runCommand("test", Path.of("/tmp"), "nonexistent-cmd-12345"))
                .isInstanceOf(RuntimeException.class);
    }
}
