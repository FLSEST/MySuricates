package com.automation.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationTestDbSessionListenerTest {

    private static final String DB_PROPERTY = "integration.db.path";
    private static final String KEY_PROPERTY = "integration.key.path";
    private static final String BACKUP_DIR_PROPERTY = "integration.backup.dir";
    private static final String SETUP_DIR_PROPERTY = "integration.setup.dir";

    private String previousDbValue;
    private String previousKeyValue;
    private String previousBackupDirValue;
    private String previousSetupDirValue;

    @BeforeEach
    void savePropertyState() {
        previousDbValue = System.getProperty(DB_PROPERTY);
        previousKeyValue = System.getProperty(KEY_PROPERTY);
        previousBackupDirValue = System.getProperty(BACKUP_DIR_PROPERTY);
        previousSetupDirValue = System.getProperty(SETUP_DIR_PROPERTY);
    }

    @AfterEach
    void restorePropertyState() {
        restore(DB_PROPERTY, previousDbValue);
        restore(KEY_PROPERTY, previousKeyValue);
        restore(BACKUP_DIR_PROPERTY, previousBackupDirValue);
        restore(SETUP_DIR_PROPERTY, previousSetupDirValue);
    }

    private static void restore(String property, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previousValue);
        }
    }

    @Test
    void sessionOpened_setsPerProcessUniqueDbPath() {
        System.clearProperty(DB_PROPERTY);
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();

        listener.launcherSessionOpened(null);

        String path = System.getProperty(DB_PROPERTY);
        assertThat(path)
                .isNotNull()
                .contains("mysuricates-it-")
                // PID in the name is what separates concurrent mvn processes
                .contains(String.valueOf(ProcessHandle.current().pid()))
                .endsWith(".db");
    }

    @Test
    void sessionOpened_respectsExternallySetPath() {
        System.setProperty(DB_PROPERTY, "/tmp/externally-managed.db");
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();

        listener.launcherSessionOpened(null);
        assertThat(System.getProperty(DB_PROPERTY)).isEqualTo("/tmp/externally-managed.db");

        // Not our path — closing must neither delete files nor clear the property
        listener.launcherSessionClosed(null);
        assertThat(System.getProperty(DB_PROPERTY)).isEqualTo("/tmp/externally-managed.db");
    }

    @Test
    void sessionClosed_deletesDbFilesAndClearsProperty() throws IOException {
        System.clearProperty(DB_PROPERTY);
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();
        listener.launcherSessionOpened(null);

        Path db = Path.of(System.getProperty(DB_PROPERTY));
        Files.writeString(db, "main");
        Files.writeString(Path.of(db + "-wal"), "wal");
        Files.writeString(Path.of(db + "-shm"), "shm");

        listener.launcherSessionClosed(null);

        assertThat(Files.exists(db)).as("db file removed").isFalse();
        assertThat(Files.exists(Path.of(db + "-wal"))).as("wal file removed").isFalse();
        assertThat(Files.exists(Path.of(db + "-shm"))).as("shm file removed").isFalse();
        assertThat(System.getProperty(DB_PROPERTY)).isNull();
    }

    @Test
    void sessionOpened_setsPerProcessUniqueKeyPath() {
        System.clearProperty(KEY_PROPERTY);
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();

        listener.launcherSessionOpened(null);

        String path = System.getProperty(KEY_PROPERTY);
        assertThat(path)
                .isNotNull()
                .contains("mysuricates-it-key-")
                .contains(String.valueOf(ProcessHandle.current().pid()))
                .endsWith(".key");
        // Must never point into the developer's real home directory
        assertThat(path).doesNotContain(System.getProperty("user.home"));
    }

    @Test
    void sessionOpened_respectsExternallySetKeyPath() {
        System.setProperty(KEY_PROPERTY, "/tmp/externally-managed.key");
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();

        listener.launcherSessionOpened(null);
        assertThat(System.getProperty(KEY_PROPERTY)).isEqualTo("/tmp/externally-managed.key");

        // Not our path — closing must neither delete the file nor clear the property
        listener.launcherSessionClosed(null);
        assertThat(System.getProperty(KEY_PROPERTY)).isEqualTo("/tmp/externally-managed.key");
    }

    @Test
    void sessionClosed_deletesKeyFileAndClearsProperty() throws IOException {
        System.clearProperty(KEY_PROPERTY);
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();
        listener.launcherSessionOpened(null);

        Path key = Path.of(System.getProperty(KEY_PROPERTY));
        Files.writeString(key, "dummy-key");

        listener.launcherSessionClosed(null);

        assertThat(Files.exists(key)).as("key file removed").isFalse();
        assertThat(System.getProperty(KEY_PROPERTY)).isNull();
    }

    @Test
    void sessionOpened_setsPerProcessUniqueBackupDir() {
        System.clearProperty(BACKUP_DIR_PROPERTY);
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();

        listener.launcherSessionOpened(null);

        String path = System.getProperty(BACKUP_DIR_PROPERTY);
        assertThat(path)
                .isNotNull()
                .contains("mysuricates-it-backups-")
                .contains(String.valueOf(ProcessHandle.current().pid()));
        // Must never point into the developer's real ~/.mysuricates/backups
        assertThat(path).doesNotContain(System.getProperty("user.home"));

        listener.launcherSessionClosed(null);
    }

    @Test
    void sessionOpened_respectsExternallySetBackupDir() {
        System.setProperty(BACKUP_DIR_PROPERTY, "/tmp/externally-managed-backups");
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();

        listener.launcherSessionOpened(null);
        assertThat(System.getProperty(BACKUP_DIR_PROPERTY)).isEqualTo("/tmp/externally-managed-backups");

        listener.launcherSessionClosed(null);
        assertThat(System.getProperty(BACKUP_DIR_PROPERTY)).isEqualTo("/tmp/externally-managed-backups");
    }

    @Test
    void sessionClosed_deletesBackupDirRecursivelyAndClearsProperty() throws IOException {
        System.clearProperty(BACKUP_DIR_PROPERTY);
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();
        listener.launcherSessionOpened(null);

        Path dir = Path.of(System.getProperty(BACKUP_DIR_PROPERTY));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mysuricates-backup-2026-01-01_00-00-00.db"), "db");
        Files.writeString(dir.resolve("mysuricates-backup-2026-01-01_00-00-00.db-wal"), "wal");

        listener.launcherSessionClosed(null);

        assertThat(Files.exists(dir)).as("backup dir removed with contents").isFalse();
        assertThat(System.getProperty(BACKUP_DIR_PROPERTY)).isNull();
    }

    @Test
    void sessionClosed_missingBackupDirIsNotAnError() {
        System.clearProperty(BACKUP_DIR_PROPERTY);
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();
        listener.launcherSessionOpened(null);

        // A context that never started the backup service leaves no directory behind
        listener.launcherSessionClosed(null);

        assertThat(System.getProperty(BACKUP_DIR_PROPERTY)).isNull();
    }

    @Test
    void sessionOpened_setsPerProcessUniqueSetupDir() {
        System.clearProperty(SETUP_DIR_PROPERTY);
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();

        listener.launcherSessionOpened(null);

        String path = System.getProperty(SETUP_DIR_PROPERTY);
        assertThat(path)
                .isNotNull()
                .contains("mysuricates-it-setup-")
                .contains(String.valueOf(ProcessHandle.current().pid()));
        // Summarization and factory instructions must never land in the developer's real ~/.mysuricates
        assertThat(path).doesNotContain(System.getProperty("user.home"));

        listener.launcherSessionClosed(null);
    }

    @Test
    void sessionOpened_respectsExternallySetSetupDir() {
        System.setProperty(SETUP_DIR_PROPERTY, "/tmp/externally-managed-setup");
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();

        listener.launcherSessionOpened(null);
        assertThat(System.getProperty(SETUP_DIR_PROPERTY)).isEqualTo("/tmp/externally-managed-setup");

        listener.launcherSessionClosed(null);
        assertThat(System.getProperty(SETUP_DIR_PROPERTY)).isEqualTo("/tmp/externally-managed-setup");
    }

    @Test
    void sessionClosed_deletesSetupDirRecursivelyAndClearsProperty() throws IOException {
        System.clearProperty(SETUP_DIR_PROPERTY);
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();
        listener.launcherSessionOpened(null);

        // The layout SummarizationSetupService and FactorySetupService create on context start
        Path dir = Path.of(System.getProperty(SETUP_DIR_PROPERTY));
        Files.createDirectories(dir.resolve("summarization/claude"));
        Files.writeString(dir.resolve("summarization/claude/CLAUDE.md"), "instructions");
        Files.createDirectories(dir.resolve("factory/codex"));
        Files.writeString(dir.resolve("factory/codex/AGENTS.md"), "instructions");

        listener.launcherSessionClosed(null);

        assertThat(Files.exists(dir)).as("setup dir removed with contents").isFalse();
        assertThat(System.getProperty(SETUP_DIR_PROPERTY)).isNull();
    }

    @Test
    void sessionOpened_dbPathExternalDoesNotSuppressKeyPath() {
        System.setProperty(DB_PROPERTY, "/tmp/externally-managed.db");
        System.clearProperty(KEY_PROPERTY);
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();

        listener.launcherSessionOpened(null);

        // The two properties are independent: an externally-set DB path must not
        // suppress generation of the (not externally set) key path.
        assertThat(System.getProperty(KEY_PROPERTY)).isNotNull().endsWith(".key");

        listener.launcherSessionClosed(null);
    }
}
