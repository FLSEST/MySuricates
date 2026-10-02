package com.automation.service.example;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The file side of the example project: the copy of {@code classpath:examples/example-project/} and
 * the README of an older copy (TODO c04ffb86).
 */
@Slf4j
final class ExampleProjectFiles {

    static final String SOURCE_ROOT = "examples/example-project/";
    static final String README = "README.md";
    /**
     * SHA-256 of every earlier bundled README, line endings ignored. A copy whose README still matches
     * one of them was never touched and gets the current text; any other README is the user's and
     * stays. Add the hash of the current README here whenever its text changes.
     * <ul>
     *   <li>e70307e8 (TODO d12c7560): "write nothing outside their own run workspace" — wrong since the
     *       TODO example leaves a done TODO behind (TODO 2d782949)</li>
     * </ul>
     */
    static final Set<String> EARLIER_README_SHA256 = Set.of(
            "fbc4da8d610401b21e99180b393c735c00c55fbbb1e2785f434977bc95d376df");

    private ExampleProjectFiles() {
    }

    /**
     * Copies the bundled files into {@code target} unless it exists. The copy is assembled in a
     * sibling directory and moved into place, so a failed copy never leaves a half-filled target
     * that the existence check would then keep forever.
     */
    static void copyBundled(Path target) {
        if (Files.exists(target)) {
            return;
        }
        Path staging = null;
        try {
            Files.createDirectories(target.getParent());
            staging = Files.createTempDirectory(target.getParent(), "." + target.getFileName() + "-");
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:" + SOURCE_ROOT + "**/*");
            for (Resource resource : resources) {
                copyResource(resource, staging);
            }
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            staging = null;
            log.info("Copied the bundled example project to {}", target);
        } catch (FileAlreadyExistsException | DirectoryNotEmptyException e) {
            log.debug("Example project directory appeared concurrently: {}", target);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not copy the example project to " + target, e);
        } finally {
            deleteQuietly(staging);
        }
    }

    /**
     * Replaces the README of an existing copy with the bundled one when it is still an earlier
     * bundled version, word for word. Best effort: the project works with any README.
     *
     * @return true when the README was replaced
     */
    static boolean refreshUntouchedReadme(Path target) {
        Path readme = target.resolve(README);
        try {
            if (!Files.isRegularFile(readme) || !EARLIER_README_SHA256.contains(sha256(Files.readString(readme)))) {
                return false;
            }
            try (InputStream in = new ClassPathResource(SOURCE_ROOT + README).getInputStream()) {
                Files.copy(in, readme, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("Replaced the unchanged README of an older example project copy: {}", readme);
            return true;
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not refresh the README of the example project copy {}", readme, e);
            return false;
        }
    }

    /** SHA-256 of the text with {@code \r\n} read as {@code \n}, as lowercase hex. */
    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void copyResource(Resource resource, Path staging) throws IOException {
        String url = resource.getURL().toString();
        int start = url.lastIndexOf(SOURCE_ROOT);
        if (start < 0 || url.endsWith("/") || !resource.isReadable()) {
            return;   // directory entry (jar) or directory (file system)
        }
        String relative = url.substring(start + SOURCE_ROOT.length());
        Path file = staging.resolve(relative).normalize();
        if (relative.isEmpty() || !file.startsWith(staging)) {
            return;
        }
        Files.createDirectories(file.getParent());
        try (InputStream in = resource.getInputStream()) {
            Files.copy(in, file);
        }
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("Could not remove the staging directory {}", directory, e);
        }
    }
}
