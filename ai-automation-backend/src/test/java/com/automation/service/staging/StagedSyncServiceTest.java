package com.automation.service.staging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** URL and key-file handling of the staged sync (TODO 84a78bb7); the sync itself: StagedSyncIntegrationTest. */
class StagedSyncServiceTest {

    @Test
    void apiBaseAcceptsHostOnlyTrailingSlashAndApiPath() {
        assertThat(StagedSyncService.apiBase("http://test:9090")).isEqualTo("http://test:9090/api/v1");
        assertThat(StagedSyncService.apiBase(" http://test:9090/ ")).isEqualTo("http://test:9090/api/v1");
        assertThat(StagedSyncService.apiBase("http://test:9090/api/v1/")).isEqualTo("http://test:9090/api/v1");
    }

    @Test
    void keyIsTrimmedAndMissingOrEmptyMeansNone(@TempDir Path dir) throws Exception {
        Path key = dir.resolve("prod.key");
        Files.writeString(key, "  secret-key\n");
        assertThat(StagedSyncService.readKey(key.toString())).isEqualTo("secret-key");

        Files.writeString(key, "\n");
        assertThat(StagedSyncService.readKey(key.toString())).isNull();
        assertThat(StagedSyncService.readKey(dir.resolve("absent.key").toString())).isNull();
        assertThat(StagedSyncService.readKey("")).isNull();
        assertThat(StagedSyncService.readKey(null)).isNull();
    }
}
