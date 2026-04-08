package com.automation.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class DataDirectoryConfigTest {

    private DataDirectoryConfig config;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        config = new DataDirectoryConfig();
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(DataDirectoryConfig.class.isAnnotationPresent(Configuration.class))
                .as("DataDirectoryConfig must be annotated with @Configuration")
                .isTrue();
    }

    // --- ensureDataDirectory: Happy path ---

    @Test
    void should_createDataDirectory_when_directoryDoesNotExist() throws Exception {
        Path dbFile = tempDir.resolve("newdata/automation.db");
        setDatasourceUrl("jdbc:sqlite:" + dbFile);

        config.ensureDataDirectory();

        assertThat(dbFile.getParent()).exists().isDirectory();
    }

    @Test
    void should_createLogsSubdirectory_when_directoryDoesNotExist() throws Exception {
        Path dbFile = tempDir.resolve("newdata/automation.db");
        setDatasourceUrl("jdbc:sqlite:" + dbFile);

        config.ensureDataDirectory();

        assertThat(dbFile.getParent().resolve("logs")).exists().isDirectory();
    }

    @Test
    void should_stripQueryParameters_when_urlContainsQueryString() throws Exception {
        Path dbFile = tempDir.resolve("querydata/automation.db");
        setDatasourceUrl("jdbc:sqlite:" + dbFile + "?journal_mode=WAL&busy_timeout=5000");

        config.ensureDataDirectory();

        assertThat(dbFile.getParent()).exists().isDirectory();
        assertThat(dbFile.getParent().resolve("logs")).exists().isDirectory();
    }

    // --- ensureDataDirectory: Idempotent ---

    @Test
    void should_notFail_when_directoryAlreadyExists() throws Exception {
        Path dataDir = tempDir.resolve("existingdata");
        Files.createDirectories(dataDir);
        Files.createDirectories(dataDir.resolve("logs"));
        Path dbFile = dataDir.resolve("automation.db");
        setDatasourceUrl("jdbc:sqlite:" + dbFile);

        assertThatNoException().isThrownBy(() -> config.ensureDataDirectory());

        assertThat(dataDir).exists().isDirectory();
        assertThat(dataDir.resolve("logs")).exists().isDirectory();
    }

    // --- ensureDataDirectory: Edge cases ---

    @Test
    void should_handleUrlWithoutQueryParams_when_noQuestionMark() throws Exception {
        Path dbFile = tempDir.resolve("plaindata/automation.db");
        setDatasourceUrl("jdbc:sqlite:" + dbFile);

        config.ensureDataDirectory();

        assertThat(dbFile.getParent()).exists().isDirectory();
    }

    @Test
    void should_notFail_when_parentDirectoryIsNull() throws Exception {
        // A path like "automation.db" without any directory part has null parent
        setDatasourceUrl("jdbc:sqlite:automation.db");

        assertThatNoException().isThrownBy(() -> config.ensureDataDirectory());
    }

    @Test
    void should_createNestedDirectories_when_multipleParentsMissing() throws Exception {
        Path dbFile = tempDir.resolve("a/b/c/automation.db");
        setDatasourceUrl("jdbc:sqlite:" + dbFile);

        config.ensureDataDirectory();

        assertThat(dbFile.getParent()).exists().isDirectory();
        assertThat(dbFile.getParent().resolve("logs")).exists().isDirectory();
    }

    @Test
    void should_handleRelativePath_when_urlContainsRelativePath() throws Exception {
        Path dbFile = tempDir.resolve("reldata/automation.db");
        setDatasourceUrl("jdbc:sqlite:" + dbFile + "?journal_mode=WAL");

        config.ensureDataDirectory();

        assertThat(dbFile.getParent()).exists().isDirectory();
    }

    // --- Helper ---

    private void setDatasourceUrl(String url) throws Exception {
        Field field = DataDirectoryConfig.class.getDeclaredField("datasourceUrl");
        field.setAccessible(true);
        field.set(config, url);
    }
}
