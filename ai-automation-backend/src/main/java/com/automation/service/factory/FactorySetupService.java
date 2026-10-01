package com.automation.service.factory;

import com.automation.config.FactoryConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sets up the factory working directories and instruction files on startup.
 * Creates ~/.mysuricates/factory/codex/AGENTS.md from bundled resource template.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FactorySetupService {

    private final FactoryConfig config;

    @PostConstruct
    public void init() {
        setupDirectory("codex", "factory/codex-factory-instructions.md", "AGENTS.md");
    }

    private void setupDirectory(String provider, String resourcePath, String targetFileName) {
        Path dir = Path.of(config.getBaseDir(), provider);
        Path targetFile = dir.resolve(targetFileName);

        try {
            Files.createDirectories(dir);

            ClassPathResource resource = new ClassPathResource(resourcePath);
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Files.writeString(targetFile, content);

            log.info("Wrote factory instructions to {}", targetFile);
        } catch (IOException e) {
            log.warn("Failed to set up factory directory for {}: {}", provider, e.getMessage());
        }
    }

    public Path getCodexWorkDir() {
        return Path.of(config.getBaseDir(), "codex");
    }
}
