package com.automation.service.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

/**
 * Reads the answer Codex wrote with {@code -o}. Only a plain file counts: Codex runs the prompt's content, in a
 * container with the answer directory mounted writable, so it could leave a link to a file of the backend's
 * user there instead; the backend must not follow it (review 2026-10-09). Opened without following links and
 * read up to a cap, so the file checked is the file read.
 */
final class CodexAnswerFile {

    /** An answer is text; far above anything Codex writes, far below a file worth exfiltrating. */
    static final long MAX_BYTES = 16L * 1024 * 1024;

    private CodexAnswerFile() {
    }

    /** The trimmed answer, or empty when there is no plain, non-empty file. */
    static Optional<String> read(Path file) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        }
        if (!attributes.isRegularFile() || attributes.size() == 0) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes((int) MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new IOException("Codex answer file exceeds " + MAX_BYTES + " bytes");
            }
            return Optional.of(new String(bytes, StandardCharsets.UTF_8).trim());
        }
    }
}
