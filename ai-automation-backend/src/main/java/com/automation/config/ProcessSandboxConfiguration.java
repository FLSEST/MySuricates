package com.automation.config;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.util.ProcessSandbox;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Hands {@link ProcessSandbox} the startup switch, the trusted python3 and the instance's secrets (TODO
 * 9525820a): the API key file, the seal and integration keys, the staging target keys, the SQLite database
 * with its journal files, and every copy of that database — it holds the API key in {@code app_settings}.
 * The list is computed per process start, so a key file or backup that appears later is covered as well.
 *
 * <p>Copies (TODO 75890a40): the configured backup directory and the {@code backups} folder of every data
 * directory (where the Stable/Test compose files and {@code staging.sh} put theirs) are hidden as a whole;
 * database files elsewhere below a data directory are found by name. The JAR the backend runs from is
 * hidden too, so that no sandboxed process replaces what the next start runs unsandboxed (TODO 4c8a0755).
 */
@Slf4j
@Component
public class ProcessSandboxConfiguration {

    /** Key files the backend writes into a data directory (current and pre-rename name). */
    static final List<String> KEY_FILES = List.of("api-key", "seal-signing.key", "integration-encryption.key");
    private static final List<String> DATA_DIRS = List.of(".mysuricates", ".autotrigger");
    private static final List<String> JOURNAL_SUFFIXES = List.of("-wal", "-shm", "-journal");
    /** The folder below a data directory that holds the backups of dev, Stable and Test. */
    static final String BACKUPS_DIR = "backups";
    /** A SQLite file or its journal ({@code x.db}, {@code x.db-wal}, {@code x.db.bak}) or a backup by its prefix. */
    private static final Pattern DATABASE_COPY = Pattern.compile(".+\\.db([-.].*)?|mysuricates-backup-.*");
    /**
     * How deep below a data directory database copies are searched: deeper than anything the platform
     * writes there ({@code backups/<env>/<file>}), shallow enough to stay out of the run workspaces' clones.
     */
    static final int COPY_SEARCH_DEPTH = 4;
    private static final List<String> KEY_FILE_PROPERTIES = List.of(
            "automation.seal.key-file", "automation.integration.key-file",
            "automation.staging.targets.test.key-file", "automation.staging.targets.prod.key-file");

    private final ModuleRegistry modules;
    private final InstanceAddress instanceAddress;
    private final Environment environment;
    private final BackupConfig backupConfig;

    public ProcessSandboxConfiguration(ModuleRegistry modules, InstanceAddress instanceAddress, Environment environment,
                                       BackupConfig backupConfig) {
        this.modules = modules;
        this.instanceAddress = instanceAddress;
        this.environment = environment;
        this.backupConfig = backupConfig;
    }

    @PostConstruct
    void configure() {
        boolean enabled = modules.isEnabled(ModuleCatalog.UNATTENDED_SANDBOX);
        Path python = enabled ? ProcessSandbox.trustedPython().orElse(null) : null;
        ProcessSandbox.configure(new ProcessSandbox.Policy(enabled, this::hiddenPaths, python));
        if (enabled && python == null) {
            log.error("Process sandbox on, but no python3 that only root can change was found at /usr/bin, /usr/local/bin"
                    + " or /bin: processes outside attended runs will not start");
        } else if (enabled) {
            log.info("Process sandbox on ({}): processes outside attended runs cannot reach {} paths", python,
                    hiddenPaths().size());
        }
    }

    List<Path> hiddenPaths() {
        return hiddenPaths(Path.of(System.getProperty("user.home")), DataHome.dir(),
                System.getProperty("java.class.path", ""));
    }

    List<Path> hiddenPaths(Path home, Path dataHome, String classPath) {
        List<Path> configured = new ArrayList<>();
        configured.add(instanceAddress.apiKeyFile());
        for (String property : KEY_FILE_PROPERTIES) {
            String value = environment.getProperty(property, "");
            if (!value.isBlank()) {
                configured.add(Path.of(value.strip()));
            }
        }
        String backups = backupConfig.getDirectory();
        if (backups != null && !backups.isBlank()) {
            configured.add(Path.of(backups.strip())); // whole copies of the database, API key included
        }
        runningJar(classPath).ifPresent(configured::add);
        List<Path> dataDirs = new ArrayList<>(List.of(dataHome));
        DATA_DIRS.forEach(name -> dataDirs.add(home.resolve(name)));
        return secretPaths(dataDirs, configured, database(environment.getProperty("spring.datasource.url", "")));
    }

    /**
     * The known key files of every data directory plus any other key file found there, its backups folder and
     * every database copy below it, the configured paths (key files, backup directory), and the database
     * with its journal files and sibling databases.
     */
    static List<Path> secretPaths(List<Path> dataDirs, List<Path> configured, Path database) {
        Set<Path> paths = new LinkedHashSet<>(configured);
        List<Path> dirs = dataDirs.stream().map(p -> p.toAbsolutePath().normalize()).distinct().toList();
        for (Path dir : dirs) {
            KEY_FILES.forEach(name -> paths.add(dir.resolve(name)));
            paths.add(dir.resolve(BACKUPS_DIR));
            filesIn(dir).filter(ProcessSandboxConfiguration::looksLikeKey).forEach(paths::add);
        }
        Set<Path> hiddenDirs = new LinkedHashSet<>(paths.stream().map(p -> p.toAbsolutePath().normalize()).toList());
        dirs.forEach(dir -> paths.addAll(databaseCopiesBelow(dir, hiddenDirs)));
        if (database != null) {
            paths.add(database);
            JOURNAL_SUFFIXES.forEach(suffix -> paths.add(Path.of(database + suffix)));
            if (database.getParent() != null) {
                filesIn(database.getParent()).filter(p -> p.getFileName().toString().contains(".db")).forEach(paths::add);
            }
        }
        return paths.stream().map(p -> p.toAbsolutePath().normalize()).distinct().toList();
    }

    /** {@code jdbc:sqlite:<path>[?…]} → the absolute file; null for anything else (in-memory, other drivers). */
    static Path database(String url) {
        String prefix = "jdbc:sqlite:";
        if (url == null || !url.startsWith(prefix)) {
            return null;
        }
        String file = url.substring(prefix.length());
        int query = file.indexOf('?');
        if (query >= 0) {
            file = file.substring(0, query);
        }
        if (file.startsWith("file:")) {
            file = file.substring("file:".length());
        }
        if (file.isBlank() || file.startsWith(":memory:")) {
            return null;
        }
        return Path.of(file).toAbsolutePath().normalize();
    }

    /**
     * Database files below {@code dir} up to {@link #COPY_SEARCH_DEPTH}, without following links. Skips what is
     * hidden anyway and every git checkout (a project's own database files are its content, not ours).
     */
    static List<Path> databaseCopiesBelow(Path dir, Set<Path> hiddenAlready) {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Path> copies = new ArrayList<>();
        try {
            Files.walkFileTree(dir, EnumSet.noneOf(FileVisitOption.class), COPY_SEARCH_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                    boolean skip = !d.equals(dir) && (hiddenAlready.contains(d.toAbsolutePath().normalize())
                            || Files.exists(d.resolve(".git"), LinkOption.NOFOLLOW_LINKS));
                    return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && DATABASE_COPY.matcher(file.getFileName().toString()).matches()) {
                        copies.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE; // unreadable entry: nothing a sandboxed process could read either
                }
            });
        } catch (IOException e) {
            log.warn("Process sandbox: could not search {} for database copies: {}", dir, e.getMessage());
        }
        return copies;
    }

    /**
     * The JAR of a {@code java -jar} start (a single class path entry) — empty for a class path of several
     * entries or directories (IDE, tests). Hiding the real file through a link such as the dev starter's
     * {@code current.jar} also keeps that link from being replaced: its directory then only allows listing.
     */
    static Optional<Path> runningJar(String classPath) {
        if (classPath == null || classPath.isBlank() || classPath.contains(File.pathSeparator)) {
            return Optional.empty();
        }
        Path jar = Path.of(classPath.strip());
        return jar.getFileName() != null && jar.getFileName().toString().endsWith(".jar") && Files.isRegularFile(jar)
                ? Optional.of(jar.toAbsolutePath().normalize()) : Optional.empty();
    }

    private static boolean looksLikeKey(Path file) {
        String name = file.getFileName().toString();
        return name.startsWith("api-key") || name.endsWith(".key") || name.contains(".key.");
    }

    private static Stream<Path> filesIn(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Stream.empty();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile).toList().stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }
}
