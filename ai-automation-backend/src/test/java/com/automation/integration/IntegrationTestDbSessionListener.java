package com.automation.integration;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gives each test JVM its own SQLite integration DB and its own integration-encryption
 * key file.
 *
 * <p>Root cause (2026-07-09) for the DB path: two quality-gate workflows ran {@code mvn test}
 * concurrently; both JVMs shared the hardcoded {@code /tmp/autotrigger-integrationtest.db}.
 * Two WAL writer processes on one file first collided ({@code SQLITE_BUSY_SNAPSHOT} during
 * Flyway migration) and then corrupted the DB ({@code SQLITE_CORRUPT}), failing 159 tests via
 * the ApplicationContext failure threshold. The pool-size=1 rule only serializes writers
 * <em>within</em> one JVM — parallel Maven processes bypass it entirely.
 *
 * <p>Root cause (2026-08-06) for the key path: {@code application-integration.yml} did not
 * override {@code automation.integration.key-file}, so every {@code @SpringBootTest} with the
 * {@code integration} profile fell through to the production default
 * ({@code ${user.home}/.autotrigger/integration-encryption.key}) and, with empty
 * {@code app_settings}, generated a fresh key straight into the developer's real
 * {@code ~/.autotrigger/}, shadowing the real key on every test run.
 *
 * <p>Registered via {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener},
 * so it runs once per test JVM before any Spring context starts. It publishes per-process
 * unique paths as the {@code integration.db.path} and {@code integration.key.path} system
 * properties, which {@code application-integration.yml} resolves in the datasource/Flyway URLs
 * and the integration-encryption key-file location respectively. On session close it deletes
 * the DB files (including {@code -wal}/{@code -shm}) and the key file, so no stale files survive
 * for the next run. Externally provided values for either property are respected and left
 * untouched (each is independent — one being external does not suppress the other).
 */
public class IntegrationTestDbSessionListener implements LauncherSessionListener {

    static final String DB_PROPERTY = "integration.db.path";
    static final String KEY_PROPERTY = "integration.key.path";

    /** Path owned by this listener; null when the property was set externally. */
    private Path ownedDbPath;

    /** Path owned by this listener; null when the property was set externally. */
    private Path ownedKeyPath;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        long pid = ProcessHandle.current().pid();
        long nanos = System.nanoTime();
        String tmpDir = System.getProperty("java.io.tmpdir");

        if (System.getProperty(DB_PROPERTY) == null) {
            ownedDbPath = Path.of(tmpDir, "autotrigger-it-" + pid + "-" + nanos + ".db");
            System.setProperty(DB_PROPERTY, ownedDbPath.toString());
        }

        if (System.getProperty(KEY_PROPERTY) == null) {
            ownedKeyPath = Path.of(tmpDir, "autotrigger-it-key-" + pid + "-" + nanos + ".key");
            System.setProperty(KEY_PROPERTY, ownedKeyPath.toString());
        }
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        if (ownedDbPath != null) {
            deleteDbFiles(ownedDbPath);
            System.clearProperty(DB_PROPERTY);
            ownedDbPath = null;
        }

        if (ownedKeyPath != null) {
            deleteIfExists(ownedKeyPath);
            System.clearProperty(KEY_PROPERTY);
            ownedKeyPath = null;
        }
    }

    private static void deleteDbFiles(Path db) {
        for (String suffix : new String[] {"", "-wal", "-shm"}) {
            deleteIfExists(Path.of(db + suffix));
        }
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Best effort — a leftover temp file must not fail the test run
            System.err.println("IntegrationTestDbSessionListener: could not delete "
                    + path + ": " + e.getMessage());
        }
    }
}
