package com.automation.service.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Service
@Slf4j
public class FileBrowserService {

    private static final Set<String> BLOCKED_PREFIXES = Set.of(
            "/etc", "/proc", "/sys", "/dev", "/var/run"
    );

    public record DirectoryEntry(String name, String path) {}
    public record BrowseResult(String current, String parent, List<DirectoryEntry> directories) {}

    public BrowseResult browseDirectory(String path) throws IOException {
        Path dirPath;
        if (path == null || path.isBlank()) {
            dirPath = Path.of(System.getProperty("user.home"));
        } else {
            dirPath = Path.of(path).normalize().toAbsolutePath();
        }

        String normalized = dirPath.toString();

        if (normalized.equals("/")) {
            throw new IllegalArgumentException("Cannot browse root directory");
        }
        for (String prefix : BLOCKED_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                throw new IllegalArgumentException("Access to system directory is restricted: " + prefix);
            }
        }

        if (!Files.isDirectory(dirPath)) {
            throw new IllegalArgumentException("Path is not a directory: " + normalized);
        }

        List<DirectoryEntry> directories = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dirPath)) {
            entries.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .forEach(p -> directories.add(new DirectoryEntry(
                            p.getFileName().toString(),
                            p.toAbsolutePath().toString()
                    )));
        }

        Path parent = dirPath.getParent();
        return new BrowseResult(normalized, parent != null ? parent.toString() : null, directories);
    }

    public void openPath(String path) throws IOException, InterruptedException {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be empty");
        }

        Path targetPath = Path.of(path).normalize().toAbsolutePath();
        String normalized = targetPath.toString();

        for (String prefix : BLOCKED_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                throw new IllegalArgumentException("Access to system directory is restricted: " + prefix);
            }
        }

        if (!Files.exists(targetPath)) {
            throw new IllegalArgumentException("Path does not exist: " + normalized);
        }

        ProcessBuilder pb = new ProcessBuilder("xdg-open", normalized);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (InputStream is = process.getInputStream()) {
            is.readAllBytes();
        }

        boolean exited = process.waitFor(3, TimeUnit.SECONDS);
        if (exited && process.exitValue() != 0) {
            log.warn("xdg-open exited with code {} for path: {}", process.exitValue(), normalized);
            throw new IOException("Failed to open path (exit code " + process.exitValue() + ")");
        }
    }
}
