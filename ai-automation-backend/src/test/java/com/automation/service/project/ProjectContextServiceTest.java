package com.automation.service.project;

import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectContextServiceTest {

    @Mock
    private AiProviderRegistry aiProviderRegistry;

    @Mock
    private AiProvider aiProvider;

    @InjectMocks
    private ProjectContextService projectContextService;

    @TempDir
    Path tempDir;

    @Nested
    class WriteProjectContextTests {

        @Test
        void withContent_writesFile() throws IOException {
            projectContextService.writeProjectContextToProject("# Context", tempDir.toString());

            Path contextFile = tempDir.resolve(".claude/rules/project-context.md");
            assertThat(contextFile).exists();
            assertThat(Files.readString(contextFile)).isEqualTo("# Context");
        }

        @Test
        void withContent_createsDirectories() {
            projectContextService.writeProjectContextToProject("content", tempDir.toString());

            assertThat(tempDir.resolve(".claude/rules")).isDirectory();
        }

        @Test
        void nullContext_deletesExistingFile() throws IOException {
            Path contextFile = tempDir.resolve(".claude/rules/project-context.md");
            Files.createDirectories(contextFile.getParent());
            Files.writeString(contextFile, "old content");

            projectContextService.writeProjectContextToProject(null, tempDir.toString());

            assertThat(contextFile).doesNotExist();
        }

        @Test
        void blankContext_deletesExistingFile() throws IOException {
            Path contextFile = tempDir.resolve(".claude/rules/project-context.md");
            Files.createDirectories(contextFile.getParent());
            Files.writeString(contextFile, "old content");

            projectContextService.writeProjectContextToProject("  ", tempDir.toString());

            assertThat(contextFile).doesNotExist();
        }

        @Test
        void nullProjectPath_doesNothing() {
            // Should not throw
            projectContextService.writeProjectContextToProject("content", null);
        }

        @Test
        void blankProjectPath_doesNothing() {
            projectContextService.writeProjectContextToProject("content", "  ");
        }

        @Test
        void nullContextNoExistingFile_noException() {
            // File does not exist, null context — should not throw
            projectContextService.writeProjectContextToProject(null, tempDir.toString());
        }
    }

    @Nested
    class GenerateContextTests {

        @BeforeEach
        void setUp() throws IOException {
            // Create a minimal project structure
            Files.writeString(tempDir.resolve("README.md"), "# Test Project\nA test project.");
            Files.writeString(tempDir.resolve("package.json"), "{\"name\": \"test\", \"version\": \"1.0.0\"}");
            Files.createDirectories(tempDir.resolve("src/main/java"));
            Files.writeString(tempDir.resolve("src/main/java/App.java"), "class App {}");
        }

        @Test
        void validProject_callsAiWithSnapshot() throws IOException {
            when(aiProviderRegistry.getDefault()).thenReturn(aiProvider);
            when(aiProvider.reason(any())).thenReturn(
                    new AiProvider.ReasoningResult("Generated context", null));

            String result = projectContextService.generateContext(tempDir.toString());

            assertThat(result).isEqualTo("Generated context");
            verify(aiProvider).reason(any());
        }

        @Test
        void validProject_snapshotContainsProbeFiles() throws IOException {
            when(aiProviderRegistry.getDefault()).thenReturn(aiProvider);
            when(aiProvider.reason(any())).thenReturn(
                    new AiProvider.ReasoningResult("ctx", null));

            projectContextService.generateContext(tempDir.toString());

            ArgumentCaptor<AiProvider.ReasoningRequest> captor =
                    ArgumentCaptor.forClass(AiProvider.ReasoningRequest.class);
            verify(aiProvider).reason(captor.capture());
            String prompt = captor.getValue().prompt();
            assertThat(prompt).contains("README.md");
            assertThat(prompt).contains("# Test Project");
            assertThat(prompt).contains("package.json");
        }

        @Test
        void validProject_snapshotContainsSrcTree() throws IOException {
            when(aiProviderRegistry.getDefault()).thenReturn(aiProvider);
            when(aiProvider.reason(any())).thenReturn(
                    new AiProvider.ReasoningResult("ctx", null));

            projectContextService.generateContext(tempDir.toString());

            ArgumentCaptor<AiProvider.ReasoningRequest> captor =
                    ArgumentCaptor.forClass(AiProvider.ReasoningRequest.class);
            verify(aiProvider).reason(captor.capture());
            assertThat(captor.getValue().prompt()).contains("src/ structure");
        }

        @Test
        void nonExistentPath_throwsIllegalArgumentException() {
            assertThatThrownBy(() -> projectContextService.generateContext("/nonexistent/path"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("/nonexistent/path");
        }

        @Test
        void aiProviderThrowsIOException_wrapsInRuntimeException() throws IOException {
            when(aiProviderRegistry.getDefault()).thenReturn(aiProvider);
            when(aiProvider.reason(any())).thenThrow(new IOException("AI error"));

            assertThatThrownBy(() -> projectContextService.generateContext(tempDir.toString()))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("AI error");
        }

        @Test
        void largeProbeFile_truncatesAt4000Chars() throws IOException {
            String longContent = "x".repeat(5000);
            Files.writeString(tempDir.resolve("README.md"), longContent);
            when(aiProviderRegistry.getDefault()).thenReturn(aiProvider);
            when(aiProvider.reason(any())).thenReturn(
                    new AiProvider.ReasoningResult("ctx", null));

            projectContextService.generateContext(tempDir.toString());

            ArgumentCaptor<AiProvider.ReasoningRequest> captor =
                    ArgumentCaptor.forClass(AiProvider.ReasoningRequest.class);
            verify(aiProvider).reason(captor.capture());
            String prompt = captor.getValue().prompt();
            assertThat(prompt).contains("(truncated)");
            assertThat(prompt).doesNotContain("x".repeat(5000));
        }

        @Test
        void hiddenDirectoriesExcluded_claudeIncluded() throws IOException {
            Files.createDirectories(tempDir.resolve(".git"));
            Files.createDirectories(tempDir.resolve(".claude"));
            Files.writeString(tempDir.resolve(".claude/settings.json"), "{}");
            when(aiProviderRegistry.getDefault()).thenReturn(aiProvider);
            when(aiProvider.reason(any())).thenReturn(
                    new AiProvider.ReasoningResult("ctx", null));

            projectContextService.generateContext(tempDir.toString());

            ArgumentCaptor<AiProvider.ReasoningRequest> captor =
                    ArgumentCaptor.forClass(AiProvider.ReasoningRequest.class);
            verify(aiProvider).reason(captor.capture());
            String prompt = captor.getValue().prompt();
            assertThat(prompt).contains(".claude/");
            // .git should be excluded from directory listing
            assertThat(prompt.substring(0, prompt.indexOf("## README.md")))
                    .doesNotContain(".git");
        }
    }
}
