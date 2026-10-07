package com.automation.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// TODO 9525820a: which paths the process sandbox hides
class ProcessSandboxConfigurationTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("hides the known key files of every data directory, other key files found there and configured ones")
    void keyFiles() throws Exception {
        Path current = Files.createDirectories(tmp.resolve(".mysuricates"));
        Path legacy = Files.createDirectories(tmp.resolve(".autotrigger"));
        Files.writeString(legacy.resolve("integration-encryption.key.bak-20260912"), "old");
        Files.writeString(legacy.resolve("notes.txt"), "not a key");
        Path configured = tmp.resolve("elsewhere/sync-test.key");

        List<Path> paths = ProcessSandboxConfiguration.secretPaths(List.of(current, legacy), List.of(configured), null);

        assertThat(paths).contains(
                current.resolve("api-key"), current.resolve("seal-signing.key"), current.resolve("integration-encryption.key"),
                legacy.resolve("api-key"), legacy.resolve("integration-encryption.key.bak-20260912"), configured);
        assertThat(paths).doesNotContain(legacy.resolve("notes.txt"));
    }

    @Test
    @DisplayName("hides the database, its journal files and other databases beside it, not its logs")
    void database() throws Exception {
        Path data = Files.createDirectories(tmp.resolve("data/logs")).getParent();
        Files.writeString(data.resolve("automation-dev.db"), "");
        Path db = data.resolve("automation.db");

        List<Path> paths = ProcessSandboxConfiguration.secretPaths(List.of(), List.of(), db);

        assertThat(paths).contains(db, Path.of(db + "-wal"), Path.of(db + "-shm"), Path.of(db + "-journal"),
                data.resolve("automation-dev.db"));
        assertThat(paths).noneMatch(p -> p.startsWith(data.resolve("logs")));
    }

    @Test
    @DisplayName("reads the database file from the SQLite JDBC URL, relative paths against the working directory")
    void databaseFromUrl() {
        assertThat(ProcessSandboxConfiguration.database("jdbc:sqlite:/srv/a/automation.db?journal_mode=WAL&busy_timeout=1"))
                .isEqualTo(Path.of("/srv/a/automation.db"));
        assertThat(ProcessSandboxConfiguration.database("jdbc:sqlite:./data/automation.db"))
                .isEqualTo(Path.of("data/automation.db").toAbsolutePath().normalize());
        assertThat(ProcessSandboxConfiguration.database("jdbc:sqlite:file:/srv/b.db?mode=rw")).isEqualTo(Path.of("/srv/b.db"));
        assertThat(ProcessSandboxConfiguration.database("jdbc:sqlite::memory:")).isNull();
        assertThat(ProcessSandboxConfiguration.database("jdbc:postgresql://db/x")).isNull();
        assertThat(ProcessSandboxConfiguration.database("")).isNull();
    }
}
