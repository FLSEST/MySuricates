package com.automation.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationTestDbSessionListenerTest {

    private static final String PROPERTY = "integration.db.path";

    private String previousValue;

    @BeforeEach
    void savePropertyState() {
        previousValue = System.getProperty(PROPERTY);
    }

    @AfterEach
    void restorePropertyState() {
        if (previousValue == null) {
            System.clearProperty(PROPERTY);
        } else {
            System.setProperty(PROPERTY, previousValue);
        }
    }

    @Test
    void sessionOpened_setsPerProcessUniqueDbPath() {
        System.clearProperty(PROPERTY);
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();

        listener.launcherSessionOpened(null);

        String path = System.getProperty(PROPERTY);
        assertThat(path)
                .isNotNull()
                .contains("autotrigger-it-")
                // PID in the name is what separates concurrent mvn processes
                .contains(String.valueOf(ProcessHandle.current().pid()))
                .endsWith(".db");
    }

    @Test
    void sessionOpened_respectsExternallySetPath() {
        System.setProperty(PROPERTY, "/tmp/externally-managed.db");
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();

        listener.launcherSessionOpened(null);
        assertThat(System.getProperty(PROPERTY)).isEqualTo("/tmp/externally-managed.db");

        // Not our path — closing must neither delete files nor clear the property
        listener.launcherSessionClosed(null);
        assertThat(System.getProperty(PROPERTY)).isEqualTo("/tmp/externally-managed.db");
    }

    @Test
    void sessionClosed_deletesDbFilesAndClearsProperty() throws IOException {
        System.clearProperty(PROPERTY);
        IntegrationTestDbSessionListener listener = new IntegrationTestDbSessionListener();
        listener.launcherSessionOpened(null);

        Path db = Path.of(System.getProperty(PROPERTY));
        Files.writeString(db, "main");
        Files.writeString(Path.of(db + "-wal"), "wal");
        Files.writeString(Path.of(db + "-shm"), "shm");

        listener.launcherSessionClosed(null);

        assertThat(Files.exists(db)).as("db file removed").isFalse();
        assertThat(Files.exists(Path.of(db + "-wal"))).as("wal file removed").isFalse();
        assertThat(Files.exists(Path.of(db + "-shm"))).as("shm file removed").isFalse();
        assertThat(System.getProperty(PROPERTY)).isNull();
    }
}
