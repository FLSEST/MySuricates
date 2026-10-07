package com.automation.config;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.util.ProcessSandbox;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Hands {@link ProcessSandbox} the startup switch and the instance's secrets (TODO 9525820a): the API key
 * file, the seal and integration keys, the staging target keys and the SQLite database with its journal
 * files. The list is computed per process start, so a key file that appears later is covered as well.
 */
@Slf4j
@Component
public class ProcessSandboxConfiguration {

    /** Key files the backend writes into a data directory (current and pre-rename name). */
    static final List<String> KEY_FILES = List.of("api-key", "seal-signing.key", "integration-encryption.key");
    private static final List<String> DATA_DIRS = List.of(".mysuricates", ".autotrigger");
    private static final List<String> JOURNAL_SUFFIXES = List.of("-wal", "-shm", "-journal");
    private static final List<String> KEY_FILE_PROPERTIES = List.of(
            "automation.seal.key-file", "automation.integration.key-file",
            "automation.staging.targets.test.key-file", "automation.staging.targets.prod.key-file");

    private final ModuleRegistry modules;
    private final InstanceAddress instanceAddress;
    private final Environment environment;

    public ProcessSandboxConfiguration(ModuleRegistry modules, InstanceAddress instanceAddress, Environment environment) {
        this.modules = modules;
        this.instanceAddress = instanceAddress;
        this.environment = environment;
    }

    @PostConstruct
    void configure() {
        boolean enabled = modules.isEnabled(ModuleCatalog.UNATTENDED_SANDBOX);
        ProcessSandbox.configure(new ProcessSandbox.Policy(enabled, this::hiddenPaths));
        if (enabled) {
            log.info("Process sandbox on: processes outside attended runs cannot reach {} paths", hiddenPaths().size());
        }
    }

    List<Path> hiddenPaths() {
        List<Path> configured = new ArrayList<>();
        configured.add(instanceAddress.apiKeyFile());
        for (String property : KEY_FILE_PROPERTIES) {
            String value = environment.getProperty(property, "");
            if (!value.isBlank()) {
                configured.add(Path.of(value.strip()));
            }
        }
        Path home = Path.of(System.getProperty("user.home"));
        List<Path> dataDirs = new ArrayList<>(List.of(DataHome.dir()));
        DATA_DIRS.forEach(name -> dataDirs.add(home.resolve(name)));
        return secretPaths(dataDirs, configured, database(environment.getProperty("spring.datasource.url", "")));
    }

    /**
     * The known key files of every data directory plus any other key file found there, the configured key
     * files, and the database with its journal files and sibling databases.
     */
    static List<Path> secretPaths(List<Path> dataDirs, List<Path> configured, Path database) {
        Set<Path> paths = new LinkedHashSet<>();
        for (Path dir : dataDirs) {
            KEY_FILES.forEach(name -> paths.add(dir.resolve(name)));
            filesIn(dir).filter(ProcessSandboxConfiguration::looksLikeKey).forEach(paths::add);
        }
        paths.addAll(configured);
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
