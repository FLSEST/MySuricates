package com.automation.service.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ExampleProjectFilesTest {

    @TempDir
    Path base;

    private static String resource(String path) throws IOException {
        try (InputStream in = ExampleProjectFilesTest.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String bundledReadme() throws IOException {
        return resource("/" + ExampleProjectFiles.SOURCE_ROOT + ExampleProjectFiles.README);
    }

    private static String earlierReadme() throws IOException {
        return resource("/example-project-earlier/README-e70307e8.txt");
    }

    @Test
    void copyBundled_createsTheTargetWithTheBundledFiles_andLeavesNoStagingDirectory() throws IOException {
        Path target = base.resolve("nested/example");

        ExampleProjectFiles.copyBundled(target);

        assertThat(target.resolve("README.md")).hasContent(bundledReadme());
        try (Stream<Path> siblings = Files.list(target.getParent())) {
            assertThat(siblings).containsExactly(target);
        }
    }

    @Test
    void copyBundled_leavesAnExistingTargetAlone() throws IOException {
        Path target = base.resolve("example");
        Files.createDirectories(target);
        Files.writeString(target.resolve("README.md"), "mine");

        ExampleProjectFiles.copyBundled(target);

        assertThat(target.resolve("README.md")).hasContent("mine");
        try (Stream<Path> files = Files.list(target)) {
            assertThat(files).hasSize(1);
        }
    }

    @Test
    void copyBundled_failsUnchecked_andLeavesNoTarget_when_theParentIsNotWritable() throws IOException {
        Path parent = Files.createDirectories(base.resolve("readonly"));
        Path target = parent.resolve("example");
        assumeTrue(parent.toFile().setWritable(false, false), "file system without write permissions");
        try {
            assumeFalse(Files.isWritable(parent), "permissions not enforced (running as root?)");

            assertThatThrownBy(() -> ExampleProjectFiles.copyBundled(target))
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("Could not copy the example project to " + target);
            assertThat(target).doesNotExist();
        } finally {
            parent.toFile().setWritable(true, false);
        }
    }

    @Test
    void refreshUntouchedReadme_replacesAnEarlierBundledReadme_evenWithCrlf() throws IOException {
        Files.writeString(base.resolve("README.md"), earlierReadme().replace("\n", "\r\n"));

        assertThat(ExampleProjectFiles.refreshUntouchedReadme(base)).isTrue();
        assertThat(base.resolve("README.md")).hasContent(bundledReadme());
    }

    @Test
    void refreshUntouchedReadme_keepsAReadmeTheUserChanged() throws IOException {
        String changed = earlierReadme() + "\nmy note\n";
        Files.writeString(base.resolve("README.md"), changed);

        assertThat(ExampleProjectFiles.refreshUntouchedReadme(base)).isFalse();
        assertThat(base.resolve("README.md")).hasContent(changed);
    }

    @Test
    void refreshUntouchedReadme_isFalse_withoutAReadmeFile() throws IOException {
        assertThat(ExampleProjectFiles.refreshUntouchedReadme(base)).isFalse();

        Files.createDirectories(base.resolve("README.md"));   // a directory is no README
        assertThat(ExampleProjectFiles.refreshUntouchedReadme(base)).isFalse();
    }

    @Test
    void refreshUntouchedReadme_isFalse_forAnUnreadableReadme() throws IOException {
        Files.write(base.resolve("README.md"), new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xc3});

        assertThat(ExampleProjectFiles.refreshUntouchedReadme(base)).isFalse();
    }

    @Test
    void sha256_isLowercaseHex_andReadsCrlfAsLf() {
        assertThat(ExampleProjectFiles.sha256(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(ExampleProjectFiles.sha256("a\r\nb\r\n")).isEqualTo(ExampleProjectFiles.sha256("a\nb\n"));
        assertThat(ExampleProjectFiles.sha256("a\nb")).isNotEqualTo(ExampleProjectFiles.sha256("a\nb\n"));
    }

    @Test
    void earlierHashes_containTheRecordedReadme_butNotTheCurrentOne() throws IOException {
        assertThat(ExampleProjectFiles.EARLIER_README_SHA256).contains(ExampleProjectFiles.sha256(earlierReadme()));
        assertThat(ExampleProjectFiles.EARLIER_README_SHA256).doesNotContain(ExampleProjectFiles.sha256(bundledReadme()));
    }
}
