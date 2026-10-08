package com.automation.config;

import com.automation.modules.ModuleRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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

    // TODO 75890a40: a backup is a whole copy of the database, and the database holds the API key
    @Test
    @DisplayName("hides the configured backup directory and the backups folder of every data directory as a whole")
    void backupDirectories() throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        Path dataHome = Files.createDirectories(tmp.resolve("data-home"));
        Path backups = Files.createDirectories(tmp.resolve("elsewhere/backups/dev"));
        Files.writeString(backups.resolve("mysuricates-backup-2026-10-08_02-00-00.db"), "copy");

        List<Path> paths = configuration(backups.toString(), "").hiddenPaths(home, dataHome, "");

        assertThat(paths).contains(backups, dataHome.resolve("backups"),
                home.resolve(".mysuricates/backups"), home.resolve(".autotrigger/backups"));
        assertThat(paths).as("the directory covers what is inside it").noneMatch(p -> p.startsWith(backups) && !p.equals(backups));
    }

    @Test
    @DisplayName("finds database copies anywhere below a data directory, not inside a git checkout or hidden folder")
    void databaseCopiesBelowDataDirectories() throws Exception {
        Path data = Files.createDirectories(tmp.resolve(".mysuricates"));
        Path manual = write(data.resolve("private/automation.db.bak"));
        Path journal = write(data.resolve("private/automation.db-wal"));
        Path stray = write(data.resolve("old/x/mysuricates-backup-2026-04-07_02-00-00"));
        Path ownDb = write(data.resolve("sources/app/automation.db"));
        write(data.resolve("sources/repo/.git/HEAD"));
        Path projectDb = write(data.resolve("sources/repo/fixture.db"));
        Path deepest = write(data.resolve("a/b/c/deepest.db"));
        Path tooDeep = write(data.resolve("a/b/c/d/too-deep.db"));
        Path insideBackups = write(data.resolve("backups/test/mysuricates-backup-2026-10-08_02-00-00.db"));
        Path notes = write(data.resolve("private/notes.txt"));
        Path dbusLike = write(data.resolve("private/session.dbx"));

        List<Path> paths = ProcessSandboxConfiguration.secretPaths(List.of(data), List.of(), null);

        assertThat(paths).contains(manual, journal, stray, ownDb, deepest, data.resolve("backups"));
        assertThat(paths).doesNotContain(projectDb, tooDeep, insideBackups, notes, dbusLike);
    }

    @Test
    @DisplayName("does not follow links while searching for database copies")
    void searchDoesNotFollowLinks() throws Exception {
        Path data = Files.createDirectories(tmp.resolve(".mysuricates"));
        Path outside = write(tmp.resolve("outside/elsewhere.db"));
        Files.createSymbolicLink(data.resolve("linked"), outside.getParent());

        List<Path> found = ProcessSandboxConfiguration.databaseCopiesBelow(data, Set.of());

        assertThat(found).isEmpty();
    }

    // TODO 4c8a0755: what the next start runs must not be replaceable from inside the sandbox
    @Test
    @DisplayName("hides the JAR of a java -jar start, nothing for a class path of several entries or directories")
    void runningJar() throws Exception {
        Path jar = write(tmp.resolve("runtime/backend-20261008.jar"));
        Path link = Files.createSymbolicLink(tmp.resolve("runtime/current.jar"), jar.getFileName());

        assertThat(ProcessSandboxConfiguration.runningJar(link.toString())).contains(link);
        assertThat(ProcessSandboxConfiguration.runningJar(jar + File.pathSeparator + tmp.resolve("other.jar"))).isEmpty();
        assertThat(ProcessSandboxConfiguration.runningJar(tmp.resolve("runtime").toString())).isEmpty();
        assertThat(ProcessSandboxConfiguration.runningJar(tmp.resolve("missing.jar").toString())).isEmpty();
        assertThat(ProcessSandboxConfiguration.runningJar("")).isEmpty();

        List<Path> paths = configuration(tmp.resolve("backups").toString(), "")
                .hiddenPaths(tmp.resolve("home"), tmp.resolve("data-home"), link.toString());
        assertThat(paths).contains(link);
    }

    private ProcessSandboxConfiguration configuration(String backupDirectory, String databaseUrl) {
        BackupConfig backups = new BackupConfig();
        backups.setDirectory(backupDirectory);
        MockEnvironment environment = new MockEnvironment().withProperty("spring.datasource.url", databaseUrl);
        return new ProcessSandboxConfiguration(ModuleRegistry.allEnabled(),
                new InstanceAddress("", "", 9090, tmp.resolve("keys/api-key").toString()), environment, backups);
    }

    private static Path write(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "x");
    }
}
