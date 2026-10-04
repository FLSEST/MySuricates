package com.automation.service.staging;

import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

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

    // ── API key of a Stable/Test container: key file, never the database ──

    @Test
    void readApiKey_readsTheKeyFileViaDockerExec_neverSql() {
        DockerContainerManager m = spy(manager);
        doReturn(new DockerContainerManager.CommandResult(0, "stable-key\n")).when(m).capture(any(String[].class));

        assertThat(m.readApiKey("mysuricates-stable", null)).isEqualTo("stable-key");
        verify(m).capture("docker", "exec", "mysuricates-stable", "sh", "-c", DockerContainerManager.KEY_FILE_SH);
        assertThat(DockerContainerManager.KEY_FILE_SH).contains("api-key").doesNotContainIgnoringCase("sqlite");
    }

    @Test
    void readApiKey_missingFile_saysWhichFileAndHowToGetIt() {
        DockerContainerManager m = spy(manager);
        doReturn(new DockerContainerManager.CommandResult(3, "/home/user/.autotrigger/api-key")).when(m).capture(any(String[].class));

        assertThatThrownBy(() -> m.readApiKey("mysuricates-test", null))
                .isInstanceOfSatisfying(LocalizedStateException.class, e -> {
                    assertThat(e.errorMessage()).isEqualTo(ErrorMessage.CONTAINER_API_KEY_MISSING);
                    assertThat(e.params()).containsEntry("file", "/home/user/.autotrigger/api-key")
                            .containsEntry("container", "mysuricates-test");
                })
                .hasMessageContaining("test-promote");
    }

    /** Stable/Test started from Dev get its key as AUTOMATION_API_KEY — ApiKeyService writes no file then. */
    @Test
    void readApiKey_missingFile_butKeyPassedAtStart_usesThatKey() {
        DockerContainerManager m = spy(manager);
        doReturn(new DockerContainerManager.CommandResult(3, "/home/user/.mysuricates/api-key")).when(m).capture(any(String[].class));

        assertThat(m.readApiKey("mysuricates-test", "dev-key")).isEqualTo("dev-key");
    }

    @Test
    void readApiKey_missingFile_andNoKeyPassed_failsClearly() {
        DockerContainerManager m = spy(manager);
        doReturn(new DockerContainerManager.CommandResult(3, "/home/user/.mysuricates/api-key")).when(m).capture(any(String[].class));

        assertThatThrownBy(() -> m.readApiKey("mysuricates-test", " "))
                .isInstanceOfSatisfying(LocalizedStateException.class,
                        e -> assertThat(e.errorMessage()).isEqualTo(ErrorMessage.CONTAINER_API_KEY_MISSING));
    }

    @Test
    void readApiKey_fileWins_overThePassedKey() {
        DockerContainerManager m = spy(manager);
        doReturn(new DockerContainerManager.CommandResult(0, "file-key")).when(m).capture(any(String[].class));

        assertThat(m.readApiKey("mysuricates-stable", "dev-key")).isEqualTo("file-key");
    }

    @Test
    void readApiKey_containerNotRunning_isUnreadable_evenWithAPassedKey() {
        DockerContainerManager m = spy(manager);
        doReturn(new DockerContainerManager.CommandResult(1, "Error response from daemon: container is not running"))
                .when(m).capture(any(String[].class));

        assertThatThrownBy(() -> m.readApiKey("mysuricates-test", "dev-key"))
                .isInstanceOfSatisfying(LocalizedStateException.class,
                        e -> assertThat(e.errorMessage()).isEqualTo(ErrorMessage.CONTAINER_API_KEY_UNREADABLE));
    }

    @Test
    void readApiKey_containerNotRunning_isUnreadable() {
        DockerContainerManager m = spy(manager);
        doReturn(new DockerContainerManager.CommandResult(1, "Error response from daemon: container is not running"))
                .when(m).capture(any(String[].class));

        assertThatThrownBy(() -> m.readApiKey("mysuricates-test", null))
                .isInstanceOfSatisfying(LocalizedStateException.class,
                        e -> assertThat(e.errorMessage()).isEqualTo(ErrorMessage.CONTAINER_API_KEY_UNREADABLE))
                .hasMessageContaining("not running");
    }

    /** The shell rule itself, run locally: legacy folder first (like DataHome), exit 3 + path without a file. */
    @Test
    void keyFileScript_followsTheDataHomeRule(@TempDir Path home) throws Exception {
        DockerContainerManager.CommandResult none = runScript(home);
        assertThat(none.exitCode()).isEqualTo(3);
        assertThat(none.output()).isEqualTo(home.resolve(".mysuricates/api-key").toString());

        Files.createDirectories(home.resolve(".mysuricates"));
        Files.writeString(home.resolve(".mysuricates/api-key"), "new-key");
        assertThat(runScript(home).output()).isEqualTo("new-key");

        Files.createDirectories(home.resolve(".autotrigger"));
        assertThat(runScript(home).exitCode()).isEqualTo(3);
        Files.writeString(home.resolve(".autotrigger/api-key"), "legacy-key");
        assertThat(runScript(home).output()).isEqualTo("legacy-key");
    }

    private static DockerContainerManager.CommandResult runScript(Path home) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", DockerContainerManager.KEY_FILE_SH);
        pb.environment().put("HOME", home.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        return new DockerContainerManager.CommandResult(p.waitFor(), out);
    }
}
