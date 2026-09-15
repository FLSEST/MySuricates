package com.automation.service.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RunContextFilesTest {

    @TempDir Path project;

    private void write(String file, String content) throws IOException {
        Path path = project.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    @Test
    void appendsProjectContextSkillsAndMemoryInAFixedOrder() throws IOException {
        write(RunContextFiles.WORKFLOW_MEMORY, "## Memory\nlearned: use mvnw");
        write(RunContextFiles.SKILLS_CONTEXT, "## Skills\nreview carefully\n");
        write(RunContextFiles.PROJECT_CONTEXT, "## Project\nSpring Boot");

        assertThat(RunContextFiles.withRunContext("Workflow instructions", project.toString())).isEqualTo(
                "Workflow instructions\n\n## Project\nSpring Boot\n\n## Skills\nreview carefully\n\n## Memory\nlearned: use mvnw");
    }

    @Test
    void worksWithoutSystemPromptAndSkipsMissingOrBlankFiles() throws IOException {
        write(RunContextFiles.SKILLS_CONTEXT, "## Skills\nonly skills");
        write(RunContextFiles.WORKFLOW_MEMORY, "   \n");

        assertThat(RunContextFiles.withRunContext(null, project.toString())).isEqualTo("## Skills\nonly skills");
        assertThat(RunContextFiles.withRunContext("  ", project.toString())).isEqualTo("## Skills\nonly skills");
    }

    @Test
    void leavesThePromptUntouchedWithoutContextOrWorkingDirectory() {
        assertThat(RunContextFiles.withRunContext("system", project.toString())).isEqualTo("system");
        assertThat(RunContextFiles.withRunContext("system", null)).isEqualTo("system");
        assertThat(RunContextFiles.withRunContext(null, "")).isNull();
    }

    @Test
    void writersUseTheSamePathsTheProvidersRead() {
        // The Claude CLI reads exactly these files from .claude/rules/ — pi and Codex must get the same set
        assertThat(RunContextFiles.ALL).allMatch(file -> file.startsWith(".claude/rules/") && file.endsWith(".md"));
        assertThat(RunContextFiles.ALL).containsExactlyInAnyOrder(
                ".claude/rules/project-context.md", ".claude/rules/skills-context.md", ".claude/rules/project-memory.md");
    }
}
