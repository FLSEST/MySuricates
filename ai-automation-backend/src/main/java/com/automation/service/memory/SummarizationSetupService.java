package com.automation.service.memory;

import com.automation.config.SummarizationConfig;
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
 * Sets up the summarization working directories and instruction files on startup.
 * Creates ~/.autotrigger/summarization/claude/CLAUDE.md and .../codex/AGENTS.md
 * from bundled resource templates.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SummarizationSetupService {

    private final SummarizationConfig config;

    @PostConstruct
    public void init() {
        setupDirectory("claude", "summarization/claude-summarizer.md", "CLAUDE.md");
        setupDirectory("codex", "summarization/codex-summarizer.md", "AGENTS.md");
    }

    private void setupDirectory(String provider, String resourcePath, String targetFileName) {
        Path dir = Path.of(config.getBaseDir(), provider);
        Path targetFile = dir.resolve(targetFileName);

        try {
            Files.createDirectories(dir);

            // Always overwrite with latest bundled instructions
            ClassPathResource resource = new ClassPathResource(resourcePath);
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Files.writeString(targetFile, content);

            log.info("Wrote summarization instructions to {}", targetFile);
        } catch (IOException e) {
            log.warn("Failed to set up summarization directory for {}: {}", provider, e.getMessage());
        }
    }

    public Path getClaudeWorkDir() {
        return Path.of(config.getBaseDir(), "claude");
    }

    public Path getCodexWorkDir() {
        return Path.of(config.getBaseDir(), "codex");
    }
}
