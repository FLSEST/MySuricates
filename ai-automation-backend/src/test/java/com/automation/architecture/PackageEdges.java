package com.automation.architecture;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Package dependency edges of the production bytecode, measured with the JDK's own {@code jdeps}.
 *
 * <p>Same pipeline as {@code scripts/public-readiness/arch-metrics.sh} (section {@code ## packages}):
 * {@code jdeps -verbose:class -filter:none target/classes}, class edges aggregated to their packages,
 * only {@code com.automation.*} on both ends, self edges dropped. The weekly architecture review and
 * the ratchets in this package therefore measure the same thing and cannot drift apart.
 *
 * <p>The measurement runs once per JVM and is shared by {@link PackageCycleRatchetTest} and
 * {@link LayerRuleTest} (jdeps needs about a second on this code base). When the pipeline cannot run
 * here – no {@code target/classes}, no {@code jdeps} next to the running JDK – the callers are
 * {@linkplain Assumptions skipped} with a message that says which part is missing, never silently
 * green: an unmeasured architecture is not a healthy one.
 *
 * <p>Known blind spot: jdeps reads the constant pool, so a dependency that only appears as a generic
 * type argument (for example {@code Page<SomeProjection>}) is erased and invisible here. This
 * measures what the bytecode really references, not what the imports say.
 */
final class PackageEdges {

    static final String ROOT_PACKAGE = "com.automation";

    private static final String PREFIX = ROOT_PACKAGE + ".";
    private static final long JDEPS_TIMEOUT_SECONDS = 120;

    private static boolean measured;
    private static Set<Edge> edges;
    private static String unavailableReason;

    private PackageEdges() {
    }

    /** A directed dependency between two {@code com.automation} packages. */
    record Edge(String from, String to) implements Comparable<Edge> {

        @Override
        public String toString() {
            return from + " -> " + to;
        }

        @Override
        public int compareTo(Edge other) {
            int byFrom = from.compareTo(other.from);
            return byFrom != 0 ? byFrom : to.compareTo(other.to);
        }
    }

    /**
     * The edges of the compiled production code, or an aborted (skipped) test with a clear reason.
     * Measured once per JVM.
     */
    static synchronized Set<Edge> ofProductionClassesOrSkip() {
        if (!measured) {
            measure();
            measured = true;
        }
        Assumptions.assumeTrue(unavailableReason == null, unavailableReason);
        return edges;
    }

    /** True when {@code aPackage} is {@code layer} itself or one of its sub packages. */
    static boolean inLayer(String aPackage, String layer) {
        return aPackage.equals(layer) || aPackage.startsWith(layer + ".");
    }

    /**
     * Class edges from {@code jdeps -verbose:class} aggregated to package edges.
     *
     * <p>Mirrors the awk filter in arch-metrics.sh: four whitespace separated fields, the second one
     * {@code ->}, both class names under {@code com.automation}; the package is the class name
     * without its last segment, and self edges are dropped.
     */
    static Set<Edge> parse(Collection<String> jdepsLines) {
        Set<Edge> parsed = new TreeSet<>();
        for (String line : jdepsLines) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length != 4 || !"->".equals(fields[1])) {
                continue;
            }
            if (!fields[0].startsWith(PREFIX) || !fields[2].startsWith(PREFIX)) {
                continue;
            }
            String from = packageOf(fields[0]);
            String to = packageOf(fields[2]);
            if (!from.equals(to)) {
                parsed.add(new Edge(from, to));
            }
        }
        return parsed;
    }

    private static String packageOf(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 ? className : className.substring(0, lastDot);
    }

    private static void measure() {
        Path classes = productionClasses();
        if (classes == null) {
            unavailableReason = "jdeps pipeline unavailable: no compiled production classes found "
                    + "(looked for target/classes and ai-automation-backend/target/classes from "
                    + Paths.get("").toAbsolutePath() + "). Run `mvn test` or `mvn compile` first.";
            return;
        }
        Path jdeps = jdepsBinary();
        if (jdeps == null) {
            unavailableReason = "jdeps pipeline unavailable: no jdeps executable in "
                    + System.getProperty("java.home") + "/bin (a JRE without the JDK tools cannot "
                    + "measure package edges).";
            return;
        }
        try {
            Process process = new ProcessBuilder(
                    jdeps.toString(), "-verbose:class", "-filter:none", classes.toString())
                    .redirectErrorStream(true)
                    .start();
            List<String> output;
            try (InputStream stdout = process.getInputStream()) {
                output = new ArrayList<>(
                        new String(stdout.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
            }
            if (!process.waitFor(JDEPS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                unavailableReason = "jdeps pipeline unavailable: jdeps did not finish within "
                        + JDEPS_TIMEOUT_SECONDS + "s on " + classes;
                return;
            }
            if (process.exitValue() != 0) {
                unavailableReason = "jdeps pipeline unavailable: jdeps exited with "
                        + process.exitValue() + " on " + classes + ". Last output: " + tail(output);
                return;
            }
            Set<Edge> parsed = parse(output);
            if (parsed.isEmpty()) {
                unavailableReason = "jdeps pipeline unavailable: jdeps reported no "
                        + ROOT_PACKAGE + " package edges for " + classes
                        + " – that is a broken measurement, not a dependency free code base.";
                return;
            }
            edges = parsed;
        } catch (IOException e) {
            unavailableReason = "jdeps pipeline unavailable: could not start jdeps (" + e + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            unavailableReason = "jdeps pipeline unavailable: interrupted while waiting for jdeps";
        }
    }

    /** Maven runs with user.dir = module dir; fall back to the repo-root layout for IDE runners. */
    private static Path productionClasses() {
        for (String candidate : List.of("target/classes", "ai-automation-backend/target/classes")) {
            Path path = Paths.get(candidate);
            if (Files.isDirectory(path)) {
                return path;
            }
        }
        return null;
    }

    private static Path jdepsBinary() {
        Path bin = Paths.get(System.getProperty("java.home"), "bin");
        for (String name : List.of("jdeps", "jdeps.exe")) {
            Path candidate = bin.resolve(name);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String tail(List<String> output) {
        List<String> lines = output.stream().filter(line -> !line.isBlank()).toList();
        return String.join(" | ", lines.subList(Math.max(0, lines.size() - 5), lines.size()));
    }
}
