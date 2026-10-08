package com.automation.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Writes the instance's key files — API key, seal signing key, integration encryption key — so that no
 * other local user can read them at any moment (TODO 94316c37).
 *
 * <p>Writing the file first and restricting it afterwards leaves a window in which it exists with the
 * umask's permissions (usually 0644). Here the content goes into a temp file in the target directory that
 * is created with 0600, which then replaces the target atomically: the key never lies in a file others
 * can read, and a reader never sees a half-written key. On a file system without POSIX permissions the
 * file cannot be restricted; that is logged as a warning instead of passing silently. A target that cannot
 * be replaced (a file bind-mounted into a container: {@code EBUSY}) is restricted to 0600 first and then
 * written in place, with a warning — as before this class existed, minus the readable window.
 */
@Slf4j
public final class SecretFiles {

    static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private SecretFiles() {
    }

    /** Moves the finished temp file onto the target; replaceable in tests. */
    @FunctionalInterface
    interface Replacer {
        void replace(Path tmp, Path target) throws IOException;
    }

    /** Creates or replaces {@code target} with {@code content}, readable and writable by its owner only. */
    public static void writeOwnerOnly(Path target, String content) throws IOException {
        writeOwnerOnly(target, content, SecretFiles::replace);
    }

    static void writeOwnerOnly(Path target, String content, Replacer replacer) throws IOException {
        Path file = target.toAbsolutePath();
        Path dir = file.getParent();
        Files.createDirectories(dir);
        Path tmp = createOwnerOnlyTempFile(dir, file.getFileName().toString());
        try {
            Files.writeString(tmp, content);
            try {
                replacer.replace(tmp, file);
            } catch (FileSystemException e) {
                if (!Files.isRegularFile(file)) {
                    throw e;
                }
                log.warn("Cannot replace {} ({}): writing the key into the existing file instead", file, e.toString());
                writeInPlace(file, content);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void writeInPlace(Path file, String content) throws IOException {
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(file, OWNER_ONLY); // before the new key goes in
        }
        Files.writeString(file, content);
    }

    private static Path createOwnerOnlyTempFile(Path dir, String prefix) throws IOException {
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempFile(dir, prefix, ".tmp", PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        }
        log.warn("{} has no POSIX permissions: the key file {} cannot be restricted to its owner", dir, prefix);
        return Files.createTempFile(dir, prefix, ".tmp");
    }

    private static void replace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Same directory, so this should not happen; the temp file is restricted already either way.
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
