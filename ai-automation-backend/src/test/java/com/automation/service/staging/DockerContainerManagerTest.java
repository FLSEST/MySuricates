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
    void containerExists_unknownContainer_returnsFalse() {
        // false with or without docker on the machine — never throws
        assertThat(manager.containerExists("nonexistent-test-container-12345")).isFalse();
    }

    @Test
    void containerExists_blankName_returnsFalse() {
        assertThat(manager.containerExists("")).isFalse();
        assertThat(manager.containerExists(null)).isFalse();
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

    @Test
    void runCommand_withEnvVars_commandCanAccessThem() {
        // env vars are merged into process environment — "printenv" reads them
        manager.runCommand("test", Path.of("/tmp"),
                java.util.Map.of("MY_TEST_VAR", "hello123"),
                "printenv", "MY_TEST_VAR");
        // No exception = success, env var was accessible
    }

    @Test
    void runCommand_emptyEnvMap_succeeds() {
        manager.runCommand("test", Path.of("/tmp"), java.util.Map.of(), "echo", "ok");
    }

    @Test
    void runCommand_workingDirectory_honored() throws Exception {
        java.nio.file.Path tmpDir = java.nio.file.Files.createTempDirectory("dcm-test");
        try {
            // Create a marker file in the temp dir
            java.nio.file.Files.writeString(tmpDir.resolve("marker.txt"), "found");
            // "cat marker.txt" will only work if working directory is correctly set
            manager.runCommand("test", tmpDir, "cat", "marker.txt");
        } finally {
            java.nio.file.Files.deleteIfExists(tmpDir.resolve("marker.txt"));
            java.nio.file.Files.deleteIfExists(tmpDir);
        }
    }

    @Test
    void isHealthy_malformedUrl_returnsFalse() {
        assertThat(manager.isHealthy("not-a-url")).isFalse();
    }

    @Test
    void isRunning_emptyName_noException() {
        // Empty container name — docker ps may match running containers, but should not throw
        manager.isRunning("");
    }

    @Test
    void runCommand_exitCode1_includesExitCodeInMessage() {
        assertThatThrownBy(() ->
                manager.runCommand("test", Path.of("/tmp"), "bash", "-c", "exit 42"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("exit 42");
    }

    @Test
    void isRunning_nullName_returnsFalse() {
        assertThat(manager.isRunning(null)).isFalse();
    }

    @Test
    void isRunning_emptyName_returnsFalse() {
        assertThat(manager.isRunning("")).isFalse();
    }

    @Test
    void isRunning_blankName_returnsFalse() {
        assertThat(manager.isRunning("   ")).isFalse();
    }
}
