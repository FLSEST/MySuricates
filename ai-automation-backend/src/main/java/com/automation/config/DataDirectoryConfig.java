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
        // Extract path from jdbc:sqlite:./data/automation.db
        String dbPath = datasourceUrl.replace("jdbc:sqlite:", "");
        Path parentDir = Path.of(dbPath).getParent();

        if (parentDir != null && !Files.exists(parentDir)) {
            try {
                Files.createDirectories(parentDir);
                log.info("Created data directory: {}", parentDir);
            } catch (Exception e) {
                log.error("Failed to create data directory: {}", parentDir, e);
            }
        }
    }
}
