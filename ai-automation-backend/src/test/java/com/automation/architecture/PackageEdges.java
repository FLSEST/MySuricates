package com.automation.architecture;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Package dependency edges of the production bytecode, measured with the JDK's own {@code jdeps}.
 *
 * <p>Same pipeline as {@code scripts/public-readiness/arch-metrics.sh} (section {@code ## packages}):
 * {@code jdeps -verbose:class -filter:none <build>/classes}, class edges aggregated to their packages,
 * only {@code com.automation.*} on both ends, self edges dropped. The weekly architecture review and
 * the ratchets in this package therefore measure the same thing and cannot drift apart.
 *
 * <p>The measured directory is <em>derived, not guessed</em>: this class's own code source is
 * {@code <build>/test-classes}, so its sibling {@code <build>/classes} is by construction the build
 * that is currently running – under Maven ({@code target/}) as well as under the IDE profile
 * {@code ide-eclipse-output} ({@code target-ide/}), where a stale {@code target/classes} from the last
 * CLI build usually sits right next to it. Relative paths are only a fallback for runners without a
 * usable code source.
 *
 * <p>The measurement runs once per JVM and is shared by {@link PackageCycleRatchetTest} and
 * {@link LayerRuleTest} (jdeps needs about a second on this code base). When the pipeline cannot run
 * here – no build output, no {@code jdeps} next to the running JDK, no edges at all – the callers are
 * {@linkplain Assumptions skipped} with a message that says which part is missing, never silently
 * green: an unmeasured architecture is not a healthy one. A non-zero jdeps exit code with edges in
 * hand counts as a partial measurement and only prints a warning, exactly like {@code arch-metrics.sh}.
 *
 * <p>Known blind spot: jdeps reads the constant pool, so a dependency that only appears as a generic
 * type argument (for example {@code Page<SomeProjection>}) is erased and invisible here. This
 * measures what the bytecode really references, not what the imports say.
 */
final class PackageEdges {

    static final String ROOT_PACKAGE = "com.automation";

    /** Only used when the code source location is unusable (exotic runners, shaded test jars). */
    static final List<Path> FALLBACK_CLASS_DIRECTORIES =
            List.of(Paths.get("target/classes"), Paths.get("ai-automation-backend/target/classes"));

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

    /**
     * Production classes of the build this test runs from: the {@code classes} directory next to the
     * given code source location, or the first existing fallback. Pure except for the directory
     * checks – the whole point is that a stale {@code target/classes} never wins over the build that
     * is actually running.
     */
    static Path resolveProductionClasses(Path codeSourceLocation, List<Path> fallbacks) {
        Path beside = siblingClasses(codeSourceLocation);
        if (beside != null && Files.isDirectory(beside)) {
            return beside;
        }
        return fallbacks.stream().filter(Files::isDirectory).findFirst().orElse(null);
    }

    /** {@code …/<build>/test-classes} to {@code …/<build>/classes}; no file system access. */
    static Path siblingClasses(Path buildOutput) {
        if (buildOutput == null) {
            return null;
        }
        Path parent = buildOutput.normalize().getParent();
        return parent == null ? null : parent.resolve("classes");
    }

    /** Where this class was loaded from – {@code <build>/test-classes} for Maven and the IDE. */
    static Path codeSourceLocation() {
        try {
            CodeSource source = PackageEdges.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            return Paths.get(source.getLocation().toURI()).normalize();
        } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException
                 | SecurityException e) {
            return null;
        }
    }

    private static String packageOf(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot < 0 ? className : className.substring(0, lastDot);
    }

    private static void measure() {
        Path classes = resolveProductionClasses(codeSourceLocation(), FALLBACK_CLASS_DIRECTORIES);
        if (classes == null) {
            unavailableReason = "jdeps pipeline unavailable: no compiled production classes found "
                    + "(looked next to the code source " + codeSourceLocation() + " and for "
                    + FALLBACK_CLASS_DIRECTORIES + " below " + Paths.get("").toAbsolutePath()
                    + "). Run `mvn test` or `mvn compile` first.";
            return;
        }
        Path jdeps = jdepsBinary();
        if (jdeps == null) {
            unavailableReason = "jdeps pipeline unavailable: no jdeps executable in "
                    + System.getProperty("java.home") + "/bin (a JRE without the JDK tools cannot "
                    + "measure package edges).";
            return;
        }
        Path capturedOutput = null;
        try {
            capturedOutput = Files.createTempFile("package-edges-jdeps", ".txt");
            Process process = new ProcessBuilder(
                    jdeps.toString(), "-verbose:class", "-filter:none", classes.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(capturedOutput.toFile()))
                    .start();
            if (!process.waitFor(JDEPS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                unavailableReason = "jdeps pipeline unavailable: jdeps did not finish within "
                        + JDEPS_TIMEOUT_SECONDS + "s on " + classes;
                return;
            }
            List<String> output = readLines(capturedOutput);
            Set<Edge> parsed = parse(output);
            int exitCode = process.exitValue();
            if (parsed.isEmpty()) {
                unavailableReason = "jdeps pipeline unavailable: jdeps (exit code " + exitCode
                        + ") reported no " + ROOT_PACKAGE + " package edges for " + classes
                        + " – that is a broken measurement, not a dependency free code base. "
                        + "Last output: " + tail(output);
                return;
            }
            if (exitCode != 0) {
                System.out.println("[PackageEdges] jdeps exited with " + exitCode + " – partial "
                        + "measurement of " + classes + " (" + parsed.size() + " package edges), "
                        + "handled like jdeps_partial in arch-metrics.sh. Last output: " + tail(output));
            }
            edges = parsed;
        } catch (IOException e) {
            unavailableReason = "jdeps pipeline unavailable: could not run jdeps (" + e + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            unavailableReason = "jdeps pipeline unavailable: interrupted while waiting for jdeps";
        } finally {
            deleteQuietly(capturedOutput);
        }
    }

    /** Process output in the platform encoding jdeps actually writes ({@code native.encoding}). */
    private static List<String> readLines(Path file) throws IOException {
        return new String(Files.readAllBytes(file), processCharset()).lines().toList();
    }

    private static Charset processCharset() {
        String encoding = System.getProperty("native.encoding");
        if (encoding != null && !encoding.isBlank()) {
            try {
                return Charset.forName(encoding);
            } catch (IllegalArgumentException unsupported) {
                return StandardCharsets.UTF_8;
            }
        }
        return StandardCharsets.UTF_8;
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

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // a leftover temp file must never turn an architecture check red
        }
    }

    private static String tail(List<String> output) {
        List<String> lines = output.stream().filter(line -> !line.isBlank()).toList();
        return lines.isEmpty()
                ? "(no output)"
                : String.join(" | ", lines.subList(Math.max(0, lines.size() - 5), lines.size()));
    }
}
