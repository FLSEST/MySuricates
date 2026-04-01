package com.automation.service.system;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileBrowserServiceTest {

    private FileBrowserService service;

    @BeforeEach
    void setUp() {
        service = new FileBrowserService();
    }

    @Nested
    class BrowseDirectoryTests {

        @Test
        void validDirectory_returnsEntries(@TempDir Path tempDir) throws Exception {
            Files.createDirectory(tempDir.resolve("alpha"));
            Files.createDirectory(tempDir.resolve("beta"));
            Files.createDirectory(tempDir.resolve("gamma"));

            var result = service.browseDirectory(tempDir.toString());

            assertThat(result.current()).isEqualTo(tempDir.toString());
            assertThat(result.directories()).hasSize(3);
            assertThat(result.directories().get(0).name()).isEqualTo("alpha");
        }

        @Test
        void nullPath_defaultsToUserHome() throws Exception {
            var result = service.browseDirectory(null);
            assertThat(result.current()).isEqualTo(System.getProperty("user.home"));
        }

        @Test
        void blankPath_defaultsToUserHome() throws Exception {
            var result = service.browseDirectory("  ");
            assertThat(result.current()).isEqualTo(System.getProperty("user.home"));
        }

        @Test
        void blockedPathEtc_throwsIllegalArgument() {
            assertThatThrownBy(() -> service.browseDirectory("/etc"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("restricted");
        }

        @Test
        void blockedPathProc_throwsIllegalArgument() {
            assertThatThrownBy(() -> service.browseDirectory("/proc"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("restricted");
        }

        @Test
        void blockedPathSys_throwsIllegalArgument() {
            assertThatThrownBy(() -> service.browseDirectory("/sys"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("restricted");
        }

        @Test
        void blockedPathDev_throwsIllegalArgument() {
            assertThatThrownBy(() -> service.browseDirectory("/dev"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("restricted");
        }

        @Test
        void blockedPathVarRun_throwsIllegalArgument() {
            assertThatThrownBy(() -> service.browseDirectory("/var/run"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("restricted");
        }

        @Test
        void rootDirectory_throwsIllegalArgument() {
            assertThatThrownBy(() -> service.browseDirectory("/"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("root directory");
        }

        @Test
        void hiddenDirectoriesExcluded(@TempDir Path tempDir) throws Exception {
            Files.createDirectory(tempDir.resolve(".hidden"));
            Files.createDirectory(tempDir.resolve("visible"));

            var result = service.browseDirectory(tempDir.toString());

            assertThat(result.directories()).hasSize(1);
            assertThat(result.directories().get(0).name()).isEqualTo("visible");
        }

        @Test
        void filesAreExcluded(@TempDir Path tempDir) throws Exception {
            Files.createDirectory(tempDir.resolve("subdir"));
            Files.createFile(tempDir.resolve("file.txt"));

            var result = service.browseDirectory(tempDir.toString());

            assertThat(result.directories()).hasSize(1);
            assertThat(result.directories().get(0).name()).isEqualTo("subdir");
        }

        @Test
        void sortedCaseInsensitive(@TempDir Path tempDir) throws Exception {
            Files.createDirectory(tempDir.resolve("Zebra"));
            Files.createDirectory(tempDir.resolve("alpha"));
            Files.createDirectory(tempDir.resolve("Beta"));

            var result = service.browseDirectory(tempDir.toString());

            assertThat(result.directories()).extracting("name")
                    .containsExactly("alpha", "Beta", "Zebra");
        }

        @Test
        void parentDirectoryReturned(@TempDir Path tempDir) throws Exception {
            Path sub = Files.createDirectory(tempDir.resolve("child"));

            var result = service.browseDirectory(sub.toString());

            assertThat(result.parent()).isEqualTo(tempDir.toString());
        }

        @Test
        void pathTraversal_blockedAfterNormalization(@TempDir Path tempDir) {
            String traversal = tempDir + "/../../etc";
            assertThatThrownBy(() -> service.browseDirectory(traversal))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("restricted");
        }
    }

    @Nested
    class OpenPathTests {

        @Test
        void nullPath_throwsIllegalArgument() {
            assertThatThrownBy(() -> service.openPath(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be empty");
        }

        @Test
        void blankPath_throwsIllegalArgument() {
            assertThatThrownBy(() -> service.openPath(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be empty");
        }

        @Test
        void blockedSystemPath_throwsIllegalArgument() {
            assertThatThrownBy(() -> service.openPath("/etc/passwd"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("restricted");
        }

        @Test
        void nonExistentPath_throwsIllegalArgument() {
            assertThatThrownBy(() -> service.openPath("/tmp/nonexistent_xyz_" + System.nanoTime()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not exist");
        }
    }
}
