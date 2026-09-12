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

    private String previousDbValue;
    private String previousKeyValue;

    @BeforeEach
    void savePropertyState() {
        previousDbValue = System.getProperty(DB_PROPERTY);
        previousKeyValue = System.getProperty(KEY_PROPERTY);
    }

    @AfterEach
    void restorePropertyState() {
        restore(DB_PROPERTY, previousDbValue);
        restore(KEY_PROPERTY, previousKeyValue);
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
                .contains("autotrigger-it-")
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
                .contains("autotrigger-it-key-")
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
