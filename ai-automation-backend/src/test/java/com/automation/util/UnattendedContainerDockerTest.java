package com.automation.util;

import com.automation.service.cli.ShellCommandService;
import com.automation.util.UnattendedContainerCommand.CliAccess;
import com.automation.util.UnattendedContainerCommand.Setup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The real thing, with dummies only (TODO 6ee57b87): a shell block and a stand-in AI CLI of an unattended run
 * start through the normal code path in a container of {@code automation-agent-workflow} and try to reach a
 * dummy database and a dummy key file, change git's config and hooks and find the Docker socket. Skipped
 * where Docker or the image is missing (build it with {@code scripts/build-agent-images.sh agent-workflow}).
 */
@EnabledOnOs(OS.LINUX)
class UnattendedContainerDockerTest {

    private static final String IMAGE = "automation-agent-workflow:latest";

    @TempDir
    Path tmp;

    private Path project;
    private Path dataDir;

    @BeforeEach
    void dockerAndDummies() throws Exception {
        assumeTrue(run(List.of("docker", "image", "inspect", IMAGE)) == 0, "Docker or the image " + IMAGE + " missing");
        project = Files.createDirectories(tmp.resolve("projects/demo"));
        Files.writeString(Files.createDirectories(project.resolve("backend/data")).resolve("automation.db"), "DUMMY-DB-CONTENT");
        Files.createDirectories(project.resolve(".git/hooks"));
        Files.writeString(project.resolve(".git/config"), "[core]\n\tbare = false\n");
        Files.writeString(project.resolve("seal-signing.key"), "DUMMY-SEAL-KEY");
        Files.writeString(Files.createDirectories(project.resolve("backend/config")).resolve("application.yml"),
                "automation:\n  hardening:\n    unattended-in-container: true\n");
        dataDir = Files.createDirectories(tmp.resolve("home/.mysuricates"));
        Files.writeString(dataDir.resolve("api-key"), "DUMMY-INSTANCE-KEY");
    }

    @AfterEach
    void off() {
        UnattendedContainer.reset();
    }

    private void on(Map<String, CliAccess> clis) throws IOException {
        Path probe = Files.createTempFile(tmp, "ids", "");
        Setup setup = new Setup(IMAGE, (Integer) Files.getAttribute(probe, "unix:uid"),
                (Integer) Files.getAttribute(probe, "unix:gid"),
                List.of(dataDir.resolve("api-key"), project.resolve("backend/data/automation.db"),
                        project.resolve("seal-signing.key")),
                tmp.resolve("home"), List.of(dataDir), List.of(project.resolve("backend/config")), "", "", 256,
                ProcessHandle.current().pid(), clis, List.of(), Map.of());
        UnattendedContainer.configure(new UnattendedContainer.Policy(true, () -> setup,
                name -> run(List.of("docker", "rm", "-f", name))));
    }

    @Test
    @DisplayName("an unattended shell block works in its project but reaches no key, database, git hook, startup config or socket")
    void shellBlockProbe() throws Exception {
        on(Map.of());
        String script = String.join("\n",
                "echo \"db=[$(cat backend/data/automation.db 2>&1)]\"",
                "echo \"seal=[$(cat seal-signing.key 2>&1)]\"",
                "echo \"key=[$(cat '" + dataDir.resolve("api-key") + "' 2>&1)]\"",
                "(echo '[core]' >> .git/config) 2>/dev/null && echo gitconfig=writable || echo gitconfig=readonly",
                "(touch .git/hooks/pre-commit) 2>/dev/null && echo hooks=writable || echo hooks=readonly",
                "test -S /var/run/docker.sock && echo socket=yes || echo socket=no",
                "(echo off >> backend/config/application.yml) 2>/dev/null && echo config=writable || echo config=readonly",
                "mv .git git.old 2>/dev/null && echo gitdir=movable || echo gitdir=pinned",
                "mv backend backend.old 2>/dev/null && echo backend=movable || echo backend=pinned",
                "mv .git/hooks hooks.old 2>/dev/null && echo hooks=movable || echo hooks=pinned",
                "echo written > out.txt",
                "echo user=$(id -u) pwd=$(pwd)");

        ShellCommandService.ShellResult result = new ShellCommandService().execute(script, project.toString(), 120);

        assertThat(result.output()).doesNotContain("DUMMY-DB-CONTENT", "DUMMY-SEAL-KEY", "DUMMY-INSTANCE-KEY")
                .contains("gitconfig=readonly", "hooks=readonly", "socket=no", "config=readonly", "pwd=" + project,
                        "gitdir=pinned", "backend=pinned", "hooks=pinned");
        assertThat(Files.readString(project.resolve("out.txt")).strip()).isEqualTo("written");
        assertThat(Files.getAttribute(project.resolve("out.txt"), "unix:uid"))
                .as("files the container writes belong to the host user").isEqualTo(Files.getAttribute(project, "unix:uid"));
        assertThat(Files.readString(project.resolve(".git/config"))).isEqualTo("[core]\n\tbare = false\n");
        assertThat(project.resolve(".git/hooks/pre-commit")).doesNotExist();
        assertThat(Files.readString(project.resolve("backend/data/automation.db"))).isEqualTo("DUMMY-DB-CONTENT");
    }

    @Test
    @DisplayName("an AI CLI gets its program and its login variable by name, and reads its prompt from stdin")
    void cliProbe() throws Exception {
        Path cli = Files.createDirectories(tmp.resolve("share/fake-cli")).resolve("claude-binary");
        Files.writeString(cli, "#!/bin/sh\necho \"login=$CLAUDE_CODE_OAUTH_TOKEN config=$CLAUDE_CONFIG_DIR prompt=$(cat)\"\n");
        Files.setPosixFilePermissions(cli, PosixFilePermissions.fromString("rwxr-xr-x"));
        on(Map.of("claude", new CliAccess(cli, List.of(), List.of(),
                Map.of("CLAUDE_CODE_OAUTH_TOKEN", "dummy-login", "CLAUDE_CONFIG_DIR", "/home/agent/.claude"))));

        ProcessBuilder pb = ChildProcessEnv.forAgent(List.of("claude", "-p"), project.toString());
        assertThat(String.join(" ", pb.command())).doesNotContain("dummy-login");
        Process process = ChildProcessEnv.start(pb);
        process.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();

        assertThat(output).contains("login=dummy-login config=/home/agent/.claude prompt=hello");
    }

    @Test
    @DisplayName("a timed-out shell block leaves no container running")
    void timeoutRemovesContainer() throws Exception {
        on(Map.of());

        ShellCommandService.ShellResult result = new ShellCommandService().execute("sleep 300", project.toString(), 3);

        assertThat(result.exitCode()).isEqualTo(124);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String running;
        do {
            Thread.sleep(250);
            running = output(List.of("docker", "ps", "-q", "--filter", "label=org.mysuricates.owner-pid=" + ProcessHandle.current().pid()));
        } while (!running.isBlank() && System.nanoTime() < deadline);
        assertThat(running).as("containers of this test still running").isBlank();
    }

    private static int run(List<String> command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(60, TimeUnit.SECONDS) ? p.exitValue() : -1;
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    private static String output(List<String> command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(30, TimeUnit.SECONDS);
        return out.strip();
    }
}
