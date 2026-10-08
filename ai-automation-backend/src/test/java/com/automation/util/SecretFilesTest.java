package com.automation.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// TODO 94316c37: key files are owner-only from the moment they exist
@EnabledOnOs({OS.LINUX, OS.MAC})
class SecretFilesTest {

    @TempDir
    Path dir;

    private static String mode(Path file) throws IOException {
        return PosixFilePermissions.toString(Files.getPosixFilePermissions(file));
    }

    private Stream<Path> leftovers() throws IOException {
        return Files.list(dir).filter(p -> p.getFileName().toString().endsWith(".tmp"));
    }

    @Test
    @DisplayName("creates the key file with 0600 and its parent directories")
    void createsOwnerOnly() throws IOException {
        Path key = dir.resolve("nested/data/api-key");

        SecretFiles.writeOwnerOnly(key, "secret");

        assertThat(Files.readString(key)).isEqualTo("secret");
        assertThat(mode(key)).isEqualTo("rw-------");
    }

    @Test
    @DisplayName("replaces a key file others could read by an owner-only one and leaves no temp file behind")
    void replacesAWiderFile() throws IOException {
        Path key = Files.writeString(dir.resolve("seal-signing.key"), "old");
        Files.setPosixFilePermissions(key, PosixFilePermissions.fromString("rw-r--r--"));

        SecretFiles.writeOwnerOnly(key, "new");

        assertThat(Files.readString(key)).isEqualTo("new");
        assertThat(mode(key)).isEqualTo("rw-------");
        try (Stream<Path> tmp = leftovers()) {
            assertThat(tmp).isEmpty();
        }
    }

    @Test
    @DisplayName("a failed write leaves neither the target nor a temp file")
    void failureLeavesNothing() throws IOException {
        Path target = Files.createDirectories(dir.resolve("api-key")); // a directory cannot be replaced by a file
        Files.writeString(target.resolve("inside"), "x");

        assertThatThrownBy(() -> SecretFiles.writeOwnerOnly(target, "k")).isInstanceOf(IOException.class);

        assertThat(target).isDirectory();
        try (Stream<Path> tmp = leftovers()) {
            assertThat(tmp).isEmpty();
        }
    }
}
