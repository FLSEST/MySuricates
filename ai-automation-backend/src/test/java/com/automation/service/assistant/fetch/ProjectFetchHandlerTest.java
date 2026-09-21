package com.automation.service.assistant.fetch;

import com.automation.entity.Project;
import com.automation.repository.ProjectRepository;
import com.automation.service.project.ProjectContextService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectFetchHandlerTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private ProjectContextService projectContextService;
    @InjectMocks private ProjectFetchHandler handler;

    @TempDir Path root;

    private Project project(String path) {
        Project p = Project.builder().id("p1").name("Alpha").path(path).build();
        when(projectRepository.findById("p1")).thenReturn(Optional.of(p));
        return p;
    }

    // ── project_context ──────────────────────────────────────────────

    @Test
    void context_cachedAiContext_isUsedWithoutRegeneration() {
        Project p = project("/srv/alpha");
        p.setAiContext("cached analysis");

        String out = handler.formatProjectContext("p1");

        assertThat(out).isEqualTo("**Project analysis: Alpha** (path: /srv/alpha)\n\ncached analysis\n");
        verifyNoInteractions(projectContextService);
        verify(projectRepository, never()).save(any());
    }

    @Test
    void context_generatedAnalysis_isCachedOnTheProject() {
        Project p = project("/srv/alpha");
        when(projectContextService.generateContext("/srv/alpha")).thenReturn("fresh analysis");

        handler.formatProjectContext("p1");

        assertThat(p.getAiContext()).isEqualTo("fresh analysis");
        verify(projectRepository).save(p);
    }

    @Test
    void context_missingProjectOrPath_andGeneratorFailure() {
        when(projectRepository.findById("nope")).thenReturn(Optional.empty());
        assertThat(handler.formatProjectContext("nope")).isEqualTo("Project 'nope' not found.\n");

        project(" ");
        assertThat(handler.formatProjectContext("p1")).isEqualTo("Project 'Alpha' has no path configured.\n");
    }

    @Test
    void context_generatorThrows_reportsMessage() {
        project("/srv/alpha");
        when(projectContextService.generateContext("/srv/alpha")).thenThrow(new IllegalStateException("disk gone"));

        assertThat(handler.formatProjectContext("p1")).isEqualTo("Project context could not be generated: disk gone\n");
    }

    // ── project_files ────────────────────────────────────────────────

    @Test
    void files_listsTopLevelAndOneSubLevel_skippingIgnoredDirs() throws IOException {
        Files.writeString(root.resolve("README.md"), "hello");
        Files.createDirectories(root.resolve("src/main"));
        Files.writeString(root.resolve("src/App.java"), "class App {}");
        Files.createDirectories(root.resolve("node_modules/lib"));
        Files.createDirectories(root.resolve("src/node_modules"));
        project(root.toString());

        String out = handler.formatProjectFiles("p1");

        assertThat(out)
                .startsWith("**Project files: Alpha** (" + root + ")\n\n")
                .contains("  README.md (5 B)\n")
                .contains("  src/\n    App.java\n    main/\n")
                .doesNotContain("node_modules")
                .contains("id=\"p1/<path>\"");
    }

    @Test
    void files_subdirectoryListingIsCappedAtTen() throws IOException {
        Path dir = Files.createDirectories(root.resolve("many"));
        for (int i = 0; i < 12; i++) Files.writeString(dir.resolve("f" + (char) ('a' + i) + ".txt"), "");
        project(root.toString());

        String out = handler.formatProjectFiles("p1");

        assertThat(out).contains("    fj.txt\n    … (more)\n").doesNotContain("fk.txt");
    }

    @Test
    void files_nonExistingPath_isReported() {
        project(root.resolve("gone").toString());

        assertThat(handler.formatProjectFiles("p1")).isEqualTo("Project path does not exist: " + root.resolve("gone") + "\n");
    }

    // ── project_file ─────────────────────────────────────────────────

    @Test
    void file_withoutSlash_isInvalidFormat() {
        assertThat(handler.formatProjectFile("p1")).startsWith("Invalid format.");
        verifyNoInteractions(projectRepository);
    }

    @Test
    void file_pathTraversal_isDenied() throws IOException {
        Path inner = Files.createDirectories(root.resolve("project"));
        Files.writeString(root.resolve("secret.txt"), "top secret");
        project(inner.toString());

        assertThat(handler.formatProjectFile("p1/../secret.txt")).isEqualTo("Access denied: path is outside the project.\n");
        assertThat(handler.formatProjectFile("p1/" + root.resolve("secret.txt")))
                .as("absolute path resolves outside the root as well")
                .isEqualTo("Access denied: path is outside the project.\n");
    }

    @Test
    void file_missingOrDirectory() throws IOException {
        Files.createDirectories(root.resolve("docs"));
        project(root.toString());

        assertThat(handler.formatProjectFile("p1/nope.md")).isEqualTo("File not found: nope.md\n");
        assertThat(handler.formatProjectFile("p1/docs")).startsWith("'docs' is a directory.");
    }

    @Test
    void file_binaryExtension_isRefused_butDotfilesAndMakefileAreText() throws IOException {
        Files.write(root.resolve("logo.png"), new byte[]{1, 2, 3});
        Files.writeString(root.resolve(".env"), "A=1");
        Files.writeString(root.resolve("Makefile"), "all:");
        project(root.toString());

        assertThat(handler.formatProjectFile("p1/logo.png")).isEqualTo("File 'logo.png' is not a text file (.png).\n");
        assertThat(handler.formatProjectFile("p1/.env")).isEqualTo("**File: .env** (3 B)\n\nA=1\n");
        assertThat(handler.formatProjectFile("p1/Makefile")).isEqualTo("**File: Makefile** (4 B)\n\nall:\n");
    }

    @Test
    void file_over500KB_isRefused() throws IOException {
        Files.writeString(root.resolve("big.txt"), "x".repeat(500_001));
        project(root.toString());

        assertThat(handler.formatProjectFile("p1/big.txt")).startsWith("File 'big.txt' is too large (").endsWith("Max 500 KB.\n");
    }

    @Test
    void file_longContent_isTruncatedAt8000Chars() throws IOException {
        Files.writeString(root.resolve("long.md"), "a".repeat(9000));
        project(root.toString());

        String out = handler.formatProjectFile("p1/long.md");

        assertThat(out).contains("a".repeat(8000)).doesNotContain("a".repeat(8001))
                .endsWith("[File truncated: 8000 of 9000 characters]\n");
    }
}
