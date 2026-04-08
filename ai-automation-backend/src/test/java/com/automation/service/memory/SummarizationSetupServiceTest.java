package com.automation.service.memory;

import com.automation.config.SummarizationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class SummarizationSetupServiceTest {

    @TempDir
    Path tempDir;

    private SummarizationConfig config;
    private SummarizationSetupService service;

    @BeforeEach
    void setUp() {
        config = new SummarizationConfig();
        config.setBaseDir(tempDir.toString());
        service = new SummarizationSetupService(config);
    }

    @Test
    void should_createClaudeDirectory_when_initCalled() {
        service.init();

        assertThat(tempDir.resolve("claude")).isDirectory();
    }

    @Test
    void should_createCodexDirectory_when_initCalled() {
        service.init();

        assertThat(tempDir.resolve("codex")).isDirectory();
    }

    @Test
    void should_writeClaudeMdFile_when_initCalled() {
        service.init();

        Path claudeMd = tempDir.resolve("claude/CLAUDE.md");
        assertThat(claudeMd).exists();
        assertThat(claudeMd).isNotEmptyFile();
    }

    @Test
    void should_writeAgentsMdFile_when_initCalled() {
        service.init();

        Path agentsMd = tempDir.resolve("codex/AGENTS.md");
        assertThat(agentsMd).exists();
        assertThat(agentsMd).isNotEmptyFile();
    }

    @Test
    void should_overwriteExistingFiles_when_initCalledAgain() throws IOException {
        // First init
        service.init();

        // Overwrite with custom content
        Path claudeMd = tempDir.resolve("claude/CLAUDE.md");
        Files.writeString(claudeMd, "custom content");

        // Second init should overwrite
        service.init();

        assertThat(claudeMd).exists();
        assertThat(Files.readString(claudeMd)).isNotEqualTo("custom content");
    }

    @Test
    void should_preserveDirectoryStructure_when_initCalledMultipleTimes() {
        service.init();
        service.init();

        assertThat(tempDir.resolve("claude")).isDirectory();
        assertThat(tempDir.resolve("codex")).isDirectory();
        assertThat(tempDir.resolve("claude/CLAUDE.md")).exists();
        assertThat(tempDir.resolve("codex/AGENTS.md")).exists();
    }

    @Test
    void should_returnClaudeSubdirectory_when_getClaudeWorkDirCalled() {
        Path result = service.getClaudeWorkDir();

        assertThat(result).isEqualTo(Path.of(tempDir.toString(), "claude"));
    }

    @Test
    void should_returnCodexSubdirectory_when_getCodexWorkDirCalled() {
        Path result = service.getCodexWorkDir();

        assertThat(result).isEqualTo(Path.of(tempDir.toString(), "codex"));
    }

    @Test
    void should_writeContentFromClasspathResource_when_initCalled() throws IOException {
        service.init();

        String claudeContent = Files.readString(tempDir.resolve("claude/CLAUDE.md"));
        String codexContent = Files.readString(tempDir.resolve("codex/AGENTS.md"));

        // Content should come from the bundled resource files
        assertThat(claudeContent).isNotBlank();
        assertThat(codexContent).isNotBlank();
    }
}
