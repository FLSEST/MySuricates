package com.automation.integration;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gives each test JVM its own SQLite integration DB.
 *
 * <p>Root cause (2026-07-09): two quality-gate workflows ran {@code mvn test} concurrently;
 * both JVMs shared the hardcoded {@code /tmp/autotrigger-integrationtest.db}. Two WAL writer
 * processes on one file first collided ({@code SQLITE_BUSY_SNAPSHOT} during Flyway migration)
 * and then corrupted the DB ({@code SQLITE_CORRUPT}), failing 159 tests via the
 * ApplicationContext failure threshold. The pool-size=1 rule only serializes writers
 * <em>within</em> one JVM — parallel Maven processes bypass it entirely.
 *
 * <p>Registered via {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener},
 * so it runs once per test JVM before any Spring context starts. It publishes a per-process
 * unique DB path as the {@code integration.db.path} system property, which
 * {@code application-integration.yml} resolves in the datasource and Flyway URLs. On session
 * close it deletes the DB files (including {@code -wal}/{@code -shm}), so no stale files
 * survive for the next run. An externally provided {@code integration.db.path} is respected
 * and left untouched.
 */
public class IntegrationTestDbSessionListener implements LauncherSessionListener {

    static final String PROPERTY = "integration.db.path";

    /** Path owned by this listener; null when the property was set externally. */
    private Path ownedDbPath;

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        if (System.getProperty(PROPERTY) != null) {
            return; // externally managed — respect it
        }
        ownedDbPath = Path.of(System.getProperty("java.io.tmpdir"),
                "autotrigger-it-" + ProcessHandle.current().pid() + "-" + System.nanoTime() + ".db");
        System.setProperty(PROPERTY, ownedDbPath.toString());
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        if (ownedDbPath == null) {
            return; // not ours to clean up
        }
        deleteDbFiles(ownedDbPath);
        System.clearProperty(PROPERTY);
        ownedDbPath = null;
    }

    private static void deleteDbFiles(Path db) {
        for (String suffix : new String[] {"", "-wal", "-shm"}) {
            try {
                Files.deleteIfExists(Path.of(db + suffix));
            } catch (IOException e) {
                // Best effort — a leftover temp file must not fail the test run
                System.err.println("IntegrationTestDbSessionListener: could not delete "
                        + db + suffix + ": " + e.getMessage());
            }
        }
    }
}
