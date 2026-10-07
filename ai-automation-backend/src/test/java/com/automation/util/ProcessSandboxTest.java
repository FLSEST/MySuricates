package com.automation.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// TODO 9525820a: processes outside attended runs cannot reach the instance's secrets
class ProcessSandboxTest {

    @AfterEach
    void reset() {
        ProcessSandbox.reset();
    }

    private static void on(Path... hidden) {
        ProcessSandbox.configure(new ProcessSandbox.Policy(true, () -> List.of(hidden)));
    }

    @Nested
    @DisplayName("deciding whether a process runs behind the sandbox")
    class Decision {

        @Test
        @DisplayName("with the switch off the command stays as it is")
        void offLeavesTheCommand() {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", "echo hi");

            ChildProcessEnv.scrub(pb);

            assertThat(pb.command()).containsExactly("/bin/bash", "-c", "echo hi");
        }

        @Test
        @DisplayName("outside an attended run the helper goes in front, inline and with every hidden path")
        void unattendedIsWrapped() {
            on(Path.of("/home/u/.mysuricates/api-key"), Path.of("/srv/data/automation.db"));
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", "echo hi");

            ChildProcessEnv.scrub(pb);

            List<String> command = pb.command();
            assertThat(command.subList(0, 4)).containsExactly("python3", "-I", "-S", "-c");
            assertThat(command.get(4)).isEqualTo(ProcessSandbox.helper()).contains("SYS_RESTRICT_SELF");
            assertThat(command.subList(5, command.size())).containsExactly(
                    "--hide", "/home/u/.mysuricates/api-key", "--hide", "/srv/data/automation.db",
                    "--", "/bin/bash", "-c", "echo hi");
        }

        @Test
        @DisplayName("an attended run starts its processes unchanged, and the mark ends with its task")
        void attendedIsNotWrapped() {
            on(Path.of("/home/u/.mysuricates/api-key"));
            ProcessBuilder inside = new ProcessBuilder("true");
            AtomicBoolean activeInside = new AtomicBoolean(true);

            ProcessSandbox.attended(true, () -> {
                activeInside.set(ProcessSandbox.active());
                ChildProcessEnv.scrub(inside);
            }).run();

            assertThat(activeInside).isFalse();
            assertThat(inside.command()).containsExactly("true");
            assertThat(ProcessSandbox.active()).as("after the run the thread is unmarked again").isTrue();
        }

        @Test
        @DisplayName("an unattended run nested in an attended thread is sandboxed, and the outer mark returns")
        void nestedMarksRestoreTheOuterOne() {
            on(Path.of("/k"));
            AtomicBoolean innerActive = new AtomicBoolean(false);
            AtomicBoolean outerAfter = new AtomicBoolean(true);

            ProcessSandbox.attended(true, () -> {
                ProcessSandbox.attended(false, () -> innerActive.set(ProcessSandbox.active())).run();
                outerAfter.set(ProcessSandbox.active());
            }).run();

            assertThat(innerActive).isTrue();
            assertThat(outerAfter).isFalse();
        }

        @Test
        @DisplayName("a switched-on sandbox without anything to hide refuses instead of starting unprotected")
        void nothingToHideIsAnError() {
            ProcessSandbox.configure(new ProcessSandbox.Policy(true, List::of));

            assertThatThrownBy(() -> ChildProcessEnv.scrub(new ProcessBuilder("true")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @EnabledOnOs(OS.LINUX)
    @DisplayName("on a Linux host with Landlock")
    class RealSandbox {

        @TempDir
        Path home;

        private Path secret;
        private Path neighbour;

        private void files() throws IOException {
            Files.createDirectories(home.resolve(".mysuricates/worktrees"));
            secret = Files.writeString(home.resolve(".mysuricates/api-key"), "instance-key");
            neighbour = Files.writeString(home.resolve(".mysuricates/worktrees/notes.txt"), "work");
            Files.writeString(home.resolve("fake.key"), "attacker");
        }

        private String run(String script) throws Exception {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", script).redirectErrorStream(true);
            pb.directory(home.toFile());
            ChildProcessEnv.scrub(pb);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            Assumptions.assumeFalse(output.contains("Landlock is not available"), "no Landlock on this host");
            Assumptions.assumeFalse(process.exitValue() == 127 && output.contains("python3"), "no python3");
            return output;
        }

        @Test
        @DisplayName("hides the secret from reading, appending, removing and replacing, but not its neighbours")
        void secretStaysHiddenAndIntact() throws Exception {
            files();
            on(secret);
            String s = secret.toString();

            String output = run(String.join("\n",
                    "cat '" + s + "' >/dev/null 2>&1 && echo READ || echo no-read",
                    "echo x >> '" + s + "' 2>/dev/null && echo APPEND || echo no-append",
                    "rm -f '" + s + "' 2>/dev/null; [ -f '" + s + "' ] && echo no-remove || echo REMOVED",
                    "mv -f fake.key '" + s + "' 2>/dev/null && echo REPLACED || echo no-replace",
                    "ln -sf /tmp/x '" + s + "' 2>/dev/null && echo SYMLINKED || echo no-symlink",
                    "ln '" + s + "' hard 2>/dev/null && echo HARDLINKED || echo no-hardlink",
                    "cat '" + neighbour + "'",
                    "echo new > '" + home.resolve(".mysuricates/worktrees/new.txt") + "' && echo created-below"));

            assertThat(output).contains("no-read", "no-append", "no-remove", "no-replace", "no-symlink",
                    "no-hardlink", "work", "created-below");
            assertThat(Files.readString(secret)).isEqualTo("instance-key");
        }

        @Test
        @DisplayName("a file that appears later next to a hidden one is not readable either")
        void laterFilesOnTheWayStayClosed() throws Exception {
            files();
            Path later = home.resolve(".mysuricates/api-key.new");
            on(secret);

            String output = run("echo x > '" + later + "' 2>/dev/null && echo CREATED || echo no-create");

            assertThat(output).contains("no-create");
            assertThat(later).doesNotExist();
        }

        @Test
        @DisplayName("an attended run reads the secret as before")
        void attendedRunIsUntouched() throws Exception {
            files();
            on(secret);
            StringBuilder output = new StringBuilder();

            ProcessSandbox.attended(true, () -> {
                try {
                    output.append(run("cat '" + secret + "'"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).run();

            assertThat(output.toString()).isEqualTo("instance-key");
        }
    }
}
