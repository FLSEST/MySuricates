package com.automation.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The directory of this instance's files outside the database: API key, seal and integration keys,
 * backups, factory and summarization setups — {@code ~/.mysuricates} of this JVM's {@code user.home}
 * (per instance under the installer).
 *
 * <p>Installations from before the rename keep {@code ~/.autotrigger} as long as that directory
 * exists: its keys decrypt the stored credentials and verify the seals, so they are never moved
 * behind the user's back. Moving its content to {@code ~/.mysuricates} and removing the old
 * directory (backend stopped) switches over. {@code scripts/lib/data-home.sh} applies the same rule.
 *
 * <p>{@code AUTOMATION_DATA_HOME} sets the directory explicitly. Spring properties reach it as
 * {@code ${automation.data-home}} ({@link DataHomeEnvironmentPostProcessor}); the Stable/Test compose
 * files get it as {@code AUTOMATION_DATA_HOME} for their host backup directories ({@link #export}).
 */
public final class DataHome {

    public static final String ENV = "AUTOMATION_DATA_HOME";
    static final String DIR = ".mysuricates";
    static final String LEGACY_DIR = ".autotrigger";

    private DataHome() {
    }

    /** The directory for the {@code user.home} of this JVM. */
    public static Path dir() {
        String explicit = System.getenv(ENV);
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit.strip());
        }
        return dir(Path.of(System.getProperty("user.home")));
    }

    static Path dir(Path userHome) {
        Path legacy = userHome.resolve(LEGACY_DIR);
        return Files.isDirectory(legacy) ? legacy : userHome.resolve(DIR);
    }

    /** Passes the directory to a child process ({@code docker compose}) unless it is set there already. */
    public static void export(Map<String, String> processEnvironment) {
        processEnvironment.putIfAbsent(ENV, dir().toString());
    }

    /** {@code dir().resolve(name)} as a string, for property defaults. */
    public static String file(String name) {
        return dir().resolve(name).toString();
    }
}
