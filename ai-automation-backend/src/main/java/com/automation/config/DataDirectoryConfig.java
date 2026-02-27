package com.automation.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
@Slf4j
public class DataDirectoryConfig {

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @PostConstruct
    public void ensureDataDirectory() {
        // Extract path from jdbc:sqlite:./data/automation.db?journal_mode=WAL&...
        String dbPath = datasourceUrl.replace("jdbc:sqlite:", "");
        int queryIdx = dbPath.indexOf('?');
        if (queryIdx >= 0) {
            dbPath = dbPath.substring(0, queryIdx);
        }
        Path parentDir = Path.of(dbPath).getParent();

        if (parentDir != null) {
            createDirIfMissing(parentDir);
            // Create logs subdirectory
            createDirIfMissing(parentDir.resolve("logs"));
        }
    }

    private void createDirIfMissing(Path dir) {
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
                log.info("Created directory: {}", dir);
            } catch (Exception e) {
                log.error("Failed to create directory: {}", dir, e);
            }
        }
    }
}
