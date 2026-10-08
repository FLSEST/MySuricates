package com.automation.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// TODO 6ee57b87: which processes run in a container, and that no container outlives its client
class UnattendedContainerTest {

    @TempDir
    Path tmp;

    @AfterEach
    void reset() {
        UnattendedContainer.reset();
        ProcessSandbox.reset();
    }

    private static UnattendedContainerCommand.Setup setup() {
        return new UnattendedContainerCommand.Setup("img", 1000, 1000, List.of(), Path.of("/nonexistent/home"), List.of(),
                List.of(), "", "", 64, 1L, Map.of(), List.of(), Map.of());
    }

    private static void on(java.util.function.Consumer<String> remover) {
        UnattendedContainer.configure(new UnattendedContainer.Policy(true, UnattendedContainerTest::setup, remover));
    }

    @Test
    @DisplayName("with the switch off a shell block stays on the host")
    void offStaysOnHost() {
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", "echo hi");

        ChildProcessEnv.isolate(pb);

        assertThat(pb.command()).containsExactly("/bin/bash", "-c", "echo hi");
    }

    @Test
    @DisplayName("outside an attended run the process becomes a container start, and the sandbox stays out of it")
    void unattendedRunsInContainer() {
        on(name -> { });
        ProcessSandbox.configure(new ProcessSandbox.Policy(true, () -> List.of(Path.of("/srv/api-key")), Path.of("/usr/bin/python3")));
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", "echo hi");

        ChildProcessEnv.isolate(pb);

        assertThat(pb.command()).startsWith("docker", "run").endsWith("img", "/bin/bash", "-c", "echo hi")
                .doesNotContain("python3");
        assertThat(UnattendedContainer.containerName(pb.command())).get().asString().startsWith("mysuricates-unattended-");
    }

    @Test
    @DisplayName("an attended run starts its processes on the host, as with the switch off")
    void attendedStaysOnHost() {
        on(name -> { });
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", "echo hi");
        AtomicReference<Boolean> active = new AtomicReference<>();

        ProcessSandbox.attended(true, () -> {
            active.set(UnattendedContainer.active());
            ChildProcessEnv.isolate(pb);
        }).run();

        assertThat(active.get()).isFalse();
        assertThat(pb.command()).containsExactly("/bin/bash", "-c", "echo hi");
        assertThat(UnattendedContainer.active()).as("an unmarked thread counts as unattended").isTrue();
    }

    @Test
    @DisplayName("the integration commands keep the sandbox: plain scrub never starts a container")
    void scrubStaysOnHost() {
        on(name -> { });
        ProcessBuilder pb = new ProcessBuilder("task", "list");

        ChildProcessEnv.scrub(pb);

        assertThat(pb.command()).containsExactly("task", "list");
    }

    @Test
    @DisplayName("only container starts built here carry a name to remove")
    void containerName() {
        assertThat(UnattendedContainer.containerName(List.of("docker", "run", "--name", "mysuricates-unattended-1", "img")))
                .contains("mysuricates-unattended-1");
        assertThat(UnattendedContainer.containerName(List.of("docker", "run", "--name", "other", "img"))).isEmpty();
        assertThat(UnattendedContainer.containerName(List.of("/bin/bash", "-c", "docker run --name mysuricates-unattended-1")))
                .isEmpty();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("once the docker client has ended — here killed like on a timeout — its container is removed")
    void removedAfterKill() throws Exception {
        Path docker = tmp.resolve("docker");
        Files.writeString(docker, "#!/bin/sh\nexec sleep 30\n");
        Files.setPosixFilePermissions(docker, PosixFilePermissions.fromString("rwx------"));
        CompletableFuture<String> removed = new CompletableFuture<>();
        on(removed::complete);
        ProcessBuilder pb = new ProcessBuilder(docker.toString(), "run", "--name", "mysuricates-unattended-k", "img");

        Process process = ChildProcessEnv.start(pb);
        assertThat(removed).isNotDone();
        process.destroyForcibly();

        assertThat(removed.get(10, TimeUnit.SECONDS)).isEqualTo("mysuricates-unattended-k");
    }
}
