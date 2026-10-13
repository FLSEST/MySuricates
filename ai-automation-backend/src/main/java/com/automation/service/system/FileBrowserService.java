package com.automation.service.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
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
    /** How long the request waits for the opener's exit code; an opener still running then is left alone. */
    private static final long OPEN_TIMEOUT_SECONDS = 3;

    public record DirectoryEntry(String name, String path) {}
    public record BrowseResult(String current, String parent, List<DirectoryEntry> directories) {}

    /** The opener program the path is appended to; tests swap in a stand-in. */
    private final List<String> openCommand;

    public FileBrowserService() {
        this(List.of("xdg-open"));
    }

    FileBrowserService(List<String> openCommand) {
        this.openCommand = List.copyOf(openCommand);
    }

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
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
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

        List<String> command = new ArrayList<>(openCommand);
        command.add(normalized);
        ProcessBuilder pb = new ProcessBuilder(command);
        // Discard instead of reading to EOF: the application xdg-open starts may inherit the output and keep
        // it open until it exits, so a read would hold the request that long and the timeout below never applied.
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process = pb.start();

        boolean exited = process.waitFor(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (exited && process.exitValue() != 0) {
            log.warn("xdg-open exited with code {} for path: {}", process.exitValue(), normalized);
            throw new IOException("Failed to open path (exit code " + process.exitValue() + ")");
        }
    }
}
