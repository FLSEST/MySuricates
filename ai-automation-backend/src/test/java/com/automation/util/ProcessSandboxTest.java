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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    /** Stands in for the interpreter found at startup; the decision tests never start it. */
    private static final Path PYTHON = Path.of("/usr/bin/python3");

    private static void on(Path... hidden) {
        onWith(PYTHON, hidden);
    }

    private static void onWith(Path python, Path... hidden) {
        ProcessSandbox.configure(new ProcessSandbox.Policy(true, () -> List.of(hidden), python));
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
            assertThat(command.subList(0, 4)).containsExactly("/usr/bin/python3", "-I", "-S", "-c");
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
            ProcessSandbox.configure(new ProcessSandbox.Policy(true, List::of, PYTHON));

            assertThatThrownBy(() -> ChildProcessEnv.scrub(new ProcessBuilder("true")))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a switched-on sandbox without a trusted python3 refuses instead of starting unprotected")
        void noTrustedInterpreterIsAnError() {
            onWith(null, Path.of("/home/u/.mysuricates/api-key"));
            ProcessBuilder pb = new ProcessBuilder("true");

            assertThatThrownBy(() -> ChildProcessEnv.scrub(pb))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("python3");
            assertThat(pb.command()).containsExactly("true");
        }

        @Test
        @DisplayName("an attended run needs no interpreter: its processes start unchanged")
        void attendedRunWithoutInterpreterStillStarts() {
            onWith(null, Path.of("/k"));
            ProcessBuilder pb = new ProcessBuilder("true");

            ProcessSandbox.attended(true, () -> ChildProcessEnv.scrub(pb)).run();

            assertThat(pb.command()).containsExactly("true");
        }
    }

    // TODO 4c8a0755: python3 runs before Landlock, so it must be a file a sandboxed process cannot change
    @Nested
    @DisplayName("finding the python3 that runs the helper")
    class Interpreter {

        @TempDir
        Path root;

        private Path executable(String name) throws IOException {
            Path file = Files.createDirectories(root.resolve("bin")).resolve(name);
            Files.writeString(file, "#!/bin/sh\n");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
            return file;
        }

        @Test
        @DisplayName("looks only at fixed absolute locations, never at PATH")
        void onlyFixedLocations() {
            assertThat(ProcessSandbox.PYTHON_CANDIDATES).isNotEmpty().allMatch(Path::isAbsolute);
        }

        @Test
        @DisplayName("skips a location without python3 and takes the next trusted one")
        void skipsMissingCandidates() throws IOException {
            Path python = executable("python3");

            Optional<Path> found = ProcessSandbox.trustedInterpreter(
                    List.of(root.resolve("missing/python3"), python), path -> true);

            assertThat(found).contains(python.toRealPath());
        }

        @Test
        @DisplayName("resolves a link to the real file and checks that file and every directory above it")
        void checksTheRealFileAndEveryLevelAbove() throws IOException {
            Path real = executable("python3.12");
            Path link = Files.createSymbolicLink(root.resolve("bin/python3"), real.getFileName());
            List<Path> checked = new ArrayList<>();

            Optional<Path> found = ProcessSandbox.trustedInterpreter(List.of(link), path -> checked.add(path));

            Path resolved = real.toRealPath();
            assertThat(found).contains(resolved);
            assertThat(checked).contains(resolved, resolved.getParent(), resolved.getRoot()).doesNotContain(link);
        }

        @Test
        @DisplayName("refuses a python3 when the file or any directory above it could be changed by others")
        void refusesWhenOneLevelIsNotTrusted() throws IOException {
            Path python = executable("python3").toRealPath();
            Path writableDirectory = python.getParent().getParent();

            assertThat(ProcessSandbox.trustedInterpreter(List.of(python), path -> !path.equals(writableDirectory))).isEmpty();
            assertThat(ProcessSandbox.trustedInterpreter(List.of(python), path -> !path.equals(python))).isEmpty();
        }

        @Test
        @DisplayName("refuses a directory or a file that cannot be executed")
        void refusesWhatCannotRun() throws IOException {
            Path notExecutable = Files.writeString(root.resolve("python3"), "");
            Files.setPosixFilePermissions(notExecutable, PosixFilePermissions.fromString("rw-r--r--"));

            assertThat(ProcessSandbox.trustedInterpreter(List.of(notExecutable, root), path -> true)).isEmpty();
        }

        @Test
        @EnabledOnOs(OS.LINUX)
        @DisplayName("a file of the backend's own user does not count as changeable only by root")
        void ownFilesAreNotTrusted() throws IOException {
            Assumptions.assumeFalse("root".equals(System.getProperty("user.name")), "test runs as root");
            Path python = executable("python3");

            assertThat(ProcessSandbox.onlyRootCanChange(python)).isFalse();
            assertThat(ProcessSandbox.trustedInterpreter(List.of(python), ProcessSandbox::onlyRootCanChange)).isEmpty();
        }

        @Test
        @EnabledOnOs(OS.LINUX)
        @DisplayName("on this host the system python3 qualifies and is returned as its real file")
        void systemPythonQualifies() throws IOException {
            Path system = Path.of("/usr/bin/python3");
            Assumptions.assumeTrue(Files.isExecutable(system), "no /usr/bin/python3");
            Assumptions.assumeTrue(ProcessSandbox.onlyRootCanChange(system.toRealPath()), "system python3 not root-owned");

            assertThat(ProcessSandbox.trustedPython()).contains(system.toRealPath());
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
        private Path python;

        @org.junit.jupiter.api.BeforeEach
        void hostPython() {
            python = ProcessSandbox.trustedPython().orElse(null);
            Assumptions.assumeTrue(python != null, "no root-owned python3 on this host");
        }

        private void hide(Path... hidden) {
            onWith(python, hidden);
        }

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
            return output;
        }

        @Test
        @DisplayName("hides the secret from reading, appending, removing and replacing, but not its neighbours")
        void secretStaysHiddenAndIntact() throws Exception {
            files();
            hide(secret);
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
            hide(secret);

            String output = run("echo x > '" + later + "' 2>/dev/null && echo CREATED || echo no-create");

            assertThat(output).contains("no-create");
            assertThat(later).doesNotExist();
        }

        @Test
        @DisplayName("an attended run reads the secret as before")
        void attendedRunIsUntouched() throws Exception {
            files();
            hide(secret);
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

        // TODO 75890a40: the backup directory holds whole copies of the database, API key included
        @Test
        @DisplayName("hides a whole directory with everything below it, also when a path inside is hidden too")
        void hiddenDirectoryCoversEverythingBelow() throws Exception {
            files();
            Path backups = Files.createDirectories(home.resolve(".mysuricates/backups/dev"));
            Files.createDirectories(home.resolve(".mysuricates/backups/stable"));
            Path copy = Files.writeString(backups.resolve("mysuricates-backup-2026-10-08_02-00-00.db"), "copy-with-key");
            Path other = Files.writeString(home.resolve(".mysuricates/backups/stable/manual.db"), "other-copy");
            hide(secret, home.resolve(".mysuricates/backups"), copy);

            String output = run(String.join("\n",
                    "cat '" + copy + "' 2>/dev/null || echo no-read-copy",
                    "cat '" + other + "' 2>/dev/null || echo no-read-other",
                    "echo x > '" + home.resolve(".mysuricates/backups/stable/new.db") + "' 2>/dev/null && echo CREATED"
                            + " || echo no-create",
                    "rm -rf '" + home.resolve(".mysuricates/backups/stable") + "' 2>/dev/null; [ -f '" + other + "' ]"
                            + " && echo no-remove || echo REMOVED",
                    "cat '" + neighbour + "'"));

            assertThat(output).contains("no-read-copy", "no-read-other", "no-create", "no-remove", "work")
                    .doesNotContain("copy-with-key", "other-copy");
        }

        @Test
        @DisplayName("a link beside the way does not open up the hidden path or a way directory it points to")
        void linksDoNotReopenHiddenPaths() throws Exception {
            files();
            Files.createSymbolicLink(home.resolve("data-link"), home.resolve(".mysuricates"));
            Files.createSymbolicLink(home.resolve("key-link"), secret);
            hide(secret);

            String output = run(String.join("\n",
                    "cat key-link 2>/dev/null || echo no-read-link",
                    "cat data-link/api-key 2>/dev/null || echo no-read-via-dir",
                    "echo x > data-link/planted 2>/dev/null && echo PLANTED || echo no-plant",
                    "cat data-link/worktrees/notes.txt"));

            assertThat(output).contains("no-read-link", "no-read-via-dir", "no-plant", "work")
                    .doesNotContain("instance-key");
        }

        // TODO 4c8a0755: the dev starter runs runtime/current.jar, a link to the newest copy
        @Test
        @DisplayName("the JAR the backend runs from, hidden through its link, can neither be changed nor relinked")
        void runningJarBehindALinkStaysIntact() throws Exception {
            files();
            Path runtime = Files.createDirectories(home.resolve("runtime"));
            Path jar = Files.writeString(runtime.resolve("backend-new.jar"), "real-backend");
            Path older = Files.writeString(runtime.resolve("backend-old.jar"), "old-backend");
            Path current = Files.createSymbolicLink(runtime.resolve("current.jar"), jar.getFileName());
            hide(secret, current);

            String output = run(String.join("\n",
                    "cd '" + runtime + "'",
                    "cat current.jar 2>/dev/null || echo no-read",
                    "echo evil > current.jar 2>/dev/null && echo OVERWRITTEN || echo no-overwrite",
                    "ln -sfn backend-old.jar current.jar 2>/dev/null && echo RELINKED || echo no-relink",
                    "mv -f backend-old.jar current.jar 2>/dev/null && echo REPLACED || echo no-replace"));

            assertThat(output).contains("no-read", "no-overwrite", "no-relink", "no-replace").doesNotContain("real-backend");
            assertThat(Files.readSymbolicLink(current)).isEqualTo(jar.getFileName());
            assertThat(Files.readString(jar)).isEqualTo("real-backend");
            assertThat(older).exists();
        }
    }
}
