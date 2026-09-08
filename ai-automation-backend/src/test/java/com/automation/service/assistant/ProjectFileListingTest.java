package com.automation.service.assistant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectFileListingTest {

    private final ProjectFileListing listing = new ProjectFileListing();

    private String render(Path root) {
        StringBuilder sb = new StringBuilder();
        listing.append(sb, root == null ? null : root.toString(), "p-1");
        return sb.toString();
    }

    @Test
    void append_blankPathOrMissingDirectory_appendsNothing(@TempDir Path dir) {
        assertThat(render(null)).isEmpty();
        StringBuilder sb = new StringBuilder();
        listing.append(sb, "  ", "p-1");
        assertThat(sb).isEmpty();
        assertThat(render(dir.resolve("does-not-exist"))).isEmpty();
    }

    @Test
    void append_emptyDirectory_appendsNothing(@TempDir Path dir) {
        assertThat(render(dir)).isEmpty();
    }

    @Test
    void append_listsTopLevelFilesSorted_omitsDirectories_andAddsFetchHint(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("b.txt"), "");
        Files.writeString(dir.resolve("a.md"), "");
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/Main.java"), "");
        Files.createDirectories(dir.resolve("node_modules"));

        String out = render(dir);

        assertThat(out).startsWith("Project files: a.md, b.txt\n");
        assertThat(out).doesNotContain("src").doesNotContain("Main.java").doesNotContain("node_modules");
        assertThat(out).contains("_(Use `<fetch type=\"project_file\" id=\"p-1/<path>\"/>` to read a file)_\n");
    }

    @Test
    void append_docsDirectory_contributesAtMostFiveFilesWithPrefix(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("docs"));
        for (int i = 1; i <= 7; i++) Files.writeString(dir.resolve("docs/d" + i + ".md"), "");
        Files.createDirectories(dir.resolve("docs/sub"));
        Files.writeString(dir.resolve("README.md"), "");

        String out = render(dir);

        assertThat(out).startsWith("Project files: README.md, docs/d1.md, docs/d2.md, docs/d3.md, docs/d4.md, docs/d5.md\n");
        assertThat(out).doesNotContain("d6.md").doesNotContain("d7.md").doesNotContain("sub");
    }

    @Test
    void append_longListing_cutAt300CharsWithEllipsis(@TempDir Path dir) throws IOException {
        for (int i = 0; i < 40; i++) Files.writeString(dir.resolve("file-with-long-name-" + i + ".txt"), "");

        String out = render(dir);
        String line = out.lines().findFirst().orElseThrow();

        assertThat(line).startsWith("Project files: ");
        assertThat(line.substring("Project files: ".length())).hasSize(301).endsWith("…");
    }
}
