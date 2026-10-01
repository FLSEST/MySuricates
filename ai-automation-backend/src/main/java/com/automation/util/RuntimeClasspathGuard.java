package com.automation.util;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Refuses to boot the server from a {@code target/classes} directory.
 *
 * <p>A JVM started with {@code -cp target/classes} (e.g. {@code mvn spring-boot:run}) loads classes
 * lazily from that directory for its whole lifetime. Every Maven build in the same checkout and every
 * IDE build (VS Code Java / Eclipse compiler) rewrites the directory underneath the running process:
 * synthetic classes disappear, MapStruct implementations get replaced by error stubs, and the JVM
 * dies later with a sticky {@code NoClassDefFoundError} (incidents 2026-07-27 and 2026-09-13).
 *
 * <p>The dev backend therefore runs from a packaged JAR copy ({@code scripts/start-mysuricates-dev.sh}).
 * This guard is the mechanical backstop for the bypass: it runs in {@code main()} before Spring starts,
 * so Spring Boot tests (which never call {@code main()}) and containers (JAR) are unaffected.
 * Escape hatch: {@code -Dautomation.runtime.allow-classes-dir=true} or
 * {@code AUTOMATION_ALLOW_CLASSES_DIR=true}.
 */
public final class RuntimeClasspathGuard {

    public static final String ALLOW_PROPERTY = "automation.runtime.allow-classes-dir";
    public static final String ALLOW_ENV = "AUTOMATION_ALLOW_CLASSES_DIR";

    public static final String MODE_JAR = "jar";
    public static final String MODE_CLASSES_DIR = "classes-dir";

    private static final Pattern CLASSES_DIR_ENTRY =
            Pattern.compile("(^|[/\\\\])target[/\\\\]classes[/\\\\]?$");

    private RuntimeClasspathGuard() {
    }

    /** {@code classes-dir} if any classpath entry is a {@code target/classes} directory, else {@code jar}. */
    public static String mode(String classPath) {
        return hasClassesDirEntry(classPath) ? MODE_CLASSES_DIR : MODE_JAR;
    }

    /**
     * @param classPath the value of {@code java.class.path}
     * @param allowFlag the escape-hatch value (property or env), {@code "true"} permits classes-dir mode
     * @return a human-readable refusal, or empty when booting is fine
     */
    public static Optional<String> refusalReason(String classPath, String allowFlag) {
        if (!hasClassesDirEntry(classPath) || "true".equalsIgnoreCase(allowFlag == null ? "" : allowFlag.trim())) {
            return Optional.empty();
        }
        return Optional.of("Refusing to start from target/classes: a running JVM would read classes lazily "
                + "from a directory that IDE and Maven builds rewrite (NoClassDefFoundError, dead workflow "
                + "runs). Start the dev backend via scripts/start-mysuricates-dev.sh restart (runs from a "
                + "JAR copy), or set -D" + ALLOW_PROPERTY + "=true / " + ALLOW_ENV + "=true if you really "
                + "know what you are doing.");
    }

    /** Resolves the escape hatch from system property first, then environment. */
    public static String allowFlagFromRuntime() {
        String prop = System.getProperty(ALLOW_PROPERTY);
        return prop != null ? prop : System.getenv(ALLOW_ENV);
    }

    private static boolean hasClassesDirEntry(String classPath) {
        if (classPath == null || classPath.isBlank()) {
            return false;
        }
        for (String entry : classPath.split(Pattern.quote(System.getProperty("path.separator", ":")))) {
            if (CLASSES_DIR_ENTRY.matcher(entry.trim()).find()) {
                return true;
            }
        }
        return false;
    }
}
