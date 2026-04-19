package com.automation.service.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentExtractionServiceTest {

    private final DocumentExtractionService service =
            new DocumentExtractionService(50 * 1024 * 1024, 5_000_000);

    @Test
    void extract_plainTextFile_returnsContent(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("notes.txt");
        Files.writeString(file, "Hello Tika\nSecond line");

        DocumentExtractionService.ExtractionResult result =
                service.extract(file.toString(), tmp.toString());

        assertThat(result.text()).contains("Hello Tika").contains("Second line");
        assertThat(result.contentType()).startsWith("text/plain");
        assertThat(result.sizeBytes()).isGreaterThan(0);
        assertThat(result.resolvedPath()).isEqualTo(file.toString());
    }

    @Test
    void extract_relativePath_resolvedAgainstProjectPath(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("doc.txt");
        Files.writeString(file, "relative content");

        DocumentExtractionService.ExtractionResult result =
                service.extract("doc.txt", tmp.toString());

        assertThat(result.text()).contains("relative content");
    }

    @Test
    void extract_htmlFile_extractsVisibleText(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("page.html");
        Files.writeString(file, "<html><body><h1>Title</h1><p>Body text</p></body></html>");

        DocumentExtractionService.ExtractionResult result =
                service.extract(file.toString(), tmp.toString());

        assertThat(result.text()).contains("Title").contains("Body text");
        assertThat(result.contentType()).contains("html");
    }

    @Test
    void extract_missingFile_throwsNotFound(@TempDir Path tmp) {
        assertThatThrownBy(() -> service.extract("missing.txt", tmp.toString()))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void extract_blankPath_throws() {
        assertThatThrownBy(() -> service.extract("   ", "/tmp"))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("file_path is required");
    }

    @Test
    void extract_pathTraversal_blocked(@TempDir Path tmp) throws IOException {
        Path sub = Files.createDirectory(tmp.resolve("project"));
        Path outside = Files.writeString(tmp.resolve("secret.txt"), "sensitive");

        assertThatThrownBy(() -> service.extract("../secret.txt", sub.toString()))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("Path traversal");

        // sanity: the outside file does exist, rejection is about the traversal, not missing file
        assertThat(Files.exists(outside)).isTrue();
    }

    @Test
    void extract_sizeLimit_enforced(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("big.txt");
        Files.writeString(file, "x".repeat(200));

        DocumentExtractionService smallLimit = new DocumentExtractionService(100, 5_000_000);

        assertThatThrownBy(() -> smallLimit.extract(file.toString(), tmp.toString()))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("size limit");
    }

    @Test
    void extract_absolutePath_worksEvenWithNullProjectPath(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("abs.txt");
        Files.writeString(file, "absolute path content");

        DocumentExtractionService.ExtractionResult result =
                service.extract(file.toAbsolutePath().toString(), null);

        assertThat(result.text()).contains("absolute path content");
    }

    @Test
    void extract_directoryInsteadOfFile_throws(@TempDir Path tmp) {
        assertThatThrownBy(() -> service.extract(tmp.toString(), null))
                .isInstanceOf(DocumentExtractionException.class)
                .hasMessageContaining("regular file");
    }

    @Test
    void extract_characterLimit_truncates(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("long.txt");
        Files.writeString(file, "a".repeat(1000));

        DocumentExtractionService truncating = new DocumentExtractionService(50 * 1024 * 1024, 100);
        DocumentExtractionService.ExtractionResult result =
                truncating.extract(file.toString(), tmp.toString());

        assertThat(result.text().length()).isLessThanOrEqualTo(100);
    }
}
