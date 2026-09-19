package com.automation.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostProcessUtilsTest {

    @Test
    void getHostUid_configured_returnsConfigured() {
        HostProcessUtils utils = new HostProcessUtils();
        ReflectionTestUtils.setField(utils, "configuredHostUid", "5000");

        assertThat(utils.getHostUid()).isEqualTo("5000");
    }

    @Test
    void getHostUid_notConfigured_runsIdCommand() {
        HostProcessUtils utils = new HostProcessUtils();
        ReflectionTestUtils.setField(utils, "configuredHostUid", null);

        String uid = utils.getHostUid();

        // On Linux, id -u returns a numeric UID
        assertThat(uid).matches("\\d+");
    }

    @Test
    void getHostUid_cached_returnsCached() {
        HostProcessUtils utils = new HostProcessUtils();
        ReflectionTestUtils.setField(utils, "configuredHostUid", null);

        String first = utils.getHostUid();
        String second = utils.getHostUid();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void drain_successfulProcess_returnsOutput() throws Exception {
        HostProcessUtils utils = new HostProcessUtils();
        Process proc = new ProcessBuilder("echo", "hello world").start();

        String output = utils.drain(proc, 10, "test");

        assertThat(output).isEqualTo("hello world");
    }

    @Test
    void drain_timeout_throwsIOException() throws Exception {
        HostProcessUtils utils = new HostProcessUtils();
        Process proc = new ProcessBuilder("sleep", "60").start();

        assertThatThrownBy(() -> utils.drain(proc, 1, "test"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");

        proc.destroyForcibly();
    }

    /**
     * The PATH lookup is hermetic on purpose: it gets its environment handed in, so the answer does
     * not depend on what happens to be installed on the machine running the build (CI has no
     * {@code claude} on PATH, the dev host has).
     */
    @Test
    void isOnPath_findsAnExecutableInAPathEntry(@TempDir Path root) throws IOException {
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path other = Files.createDirectories(root.resolve("empty"));
        executable(bin.resolve("claude"));

        String path = other + File.pathSeparator + bin;
        assertThat(HostProcessUtils.resolvesOnPath("claude", path, null)).isTrue();
        assertThat(HostProcessUtils.resolvesOnPath("claude", other.toString(), null)).isFalse();
    }

    @Test
    void isOnPath_ignoresDirectoriesNonExecutablesAndBrokenEntries(@TempDir Path root) throws IOException {
        Path bin = Files.createDirectories(root.resolve("bin"));
        Files.createDirectories(bin.resolve("codex"));                    // a directory of that name
        Files.writeString(bin.resolve("pi"), "not executable");           // present, but not executable

        assertThat(HostProcessUtils.resolvesOnPath("codex", bin.toString(), null)).isFalse();
        assertThat(HostProcessUtils.resolvesOnPath("pi", bin.toString(), null)).isFalse();
        // A PATH entry that is not a usable path at all (NUL byte) must not throw out of the lookup.
        assertThat(HostProcessUtils.resolvesOnPath("claude", '\0' + "broken", null)).isFalse();
        assertThat(HostProcessUtils.resolvesOnPath("claude", null, null)).isFalse();
        assertThat(HostProcessUtils.resolvesOnPath(null, bin.toString(), null)).isFalse();
        assertThat(HostProcessUtils.resolvesOnPath("  ", bin.toString(), null)).isFalse();
    }

    /** A name carrying a separator is a path, never searched on PATH — the rule execvp follows. */
    @Test
    void isOnPath_treatsANameWithASeparatorAsAPath(@TempDir Path root) throws IOException {
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path binary = executable(bin.resolve("claude"));

        assertThat(HostProcessUtils.resolvesOnPath(binary.toString(), "", null)).isTrue();
        assertThat(HostProcessUtils.resolvesOnPath(root + "/bin/nope", bin.toString(), null)).isFalse();
    }

    /** PATHEXT exists only on Windows; handing it in shows the name variants are honoured. */
    @Test
    void isOnPath_honoursPathExtensionsWhenTheEnvironmentHasThem(@TempDir Path root) throws IOException {
        Path bin = Files.createDirectories(root.resolve("bin"));
        executable(bin.resolve("claude.cmd"));

        assertThat(HostProcessUtils.resolvesOnPath("claude", bin.toString(), null)).isFalse();
        assertThat(HostProcessUtils.resolvesOnPath("claude", bin.toString(),
                ".com" + File.pathSeparator + ".cmd")).isTrue();
    }

    private static Path executable(Path file) throws IOException {
        Files.writeString(file, "#!/bin/sh\n");
        assertThat(file.toFile().setExecutable(true)).isTrue();
        assertThat(Files.isExecutable(file)).isTrue();
        return file;
    }
}
