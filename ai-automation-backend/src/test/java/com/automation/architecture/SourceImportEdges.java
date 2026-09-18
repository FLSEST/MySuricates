package com.automation.architecture;

import com.automation.architecture.PackageEdges.Edge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Package dependency edges read from the <em>source</em> imports – the second view next to
 * {@link PackageEdges}, which measures the compiled bytecode with jdeps.
 *
 * <p>Why two views: jdeps reads the constant pool, so a dependency that only ever appears as a
 * generic type argument is erased and invisible ({@code Page<SomeProjection>} needs no reference to
 * the projection at all). An import scan sees exactly that case – and is blind to everything jdeps
 * is good at: fully qualified names without an import, types reached only through inheritance,
 * reflection, and generated code. Neither view subsumes the other, so {@link LayerRuleTest} runs
 * the same rules against both.
 *
 * <p>What still escapes both: a fully qualified name <em>inside</em> a generic type argument
 * ({@code Page<com.automation.repository.Foo>} written without an import). The bytecode erases it
 * and there is no import line to find. That is a real hole, not a covered case – the two views are
 * complementary, not a complete net.
 *
 * <p>Parsing is deliberately literal, not a Java parser: a line counts as an import when it starts
 * with {@code "import "} in column 0, which is where every import in this code base sits. Text in
 * comments or string literals is indented and therefore never mistaken for one. The price is that
 * an indented real import would be missed, so {@link Scan#indentedImports()} counts those lines and
 * {@link LayerRuleTest} prints them – a silently growing blind spot is worse than a noisy one.
 *
 * <p>The package of an imported type is its longest prefix of lower-case segments
 * ({@code com.automation.repository.Foo} and the static {@code com.automation.service.Foo.bar} both
 * yield their package), so static imports and {@code .*} imports land in the right layer.
 */
final class SourceImportEdges {

    static final String ROOT_PACKAGE = PackageEdges.ROOT_PACKAGE;

    /** Maven runs with user.dir = module dir; the second entry is the repo-root layout of IDE runners. */
    static final List<Path> SOURCE_ROOTS =
            List.of(Paths.get("src/main/java"), Paths.get("ai-automation-backend/src/main/java"));

    private static final String PREFIX = ROOT_PACKAGE + ".";

    private SourceImportEdges() {
    }

    /**
     * Result of a scan. {@code classes} is the number of scanned {@code com.automation} source
     * files – a scan that found nothing is a broken measurement, not a clean architecture, so the
     * callers assert on it. {@code indentedImports} counts lines that read like an import but do
     * not start in column 0: this parser skips them on purpose (see class comment), so a non-zero
     * count means the blind spot has grown and the caller should say so out loud.
     */
    record Scan(Set<Edge> edges, int classes, int indentedImports) {
    }

    /** Scans the production sources of this module. */
    static Scan ofProductionSources() throws IOException {
        return scan(sourceRoot());
    }

    /** Scans every {@code *.java} file below {@code root}; pure apart from reading those files. */
    static Scan scan(Path root) throws IOException {
        Set<Edge> edges = new TreeSet<>();
        int classes = 0;
        int indented = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (declaredPackage(lines) == null) {
                    continue;
                }
                classes++;
                edges.addAll(edgesOf(lines));
                indented += countIndentedImports(lines);
            }
        }
        return new Scan(edges, classes, indented);
    }

    /** Lines this parser ignores although they look like an import of a {@code com.automation} type. */
    static int countIndentedImports(List<String> lines) {
        int found = 0;
        for (String line : lines) {
            if (!line.startsWith("import ") && line.stripLeading().startsWith("import ")
                    && line.contains(PREFIX)) {
                found++;
            }
        }
        return found;
    }

    /** Edges of a single source file: its own package to every {@code com.automation} package it imports. */
    static Set<Edge> edgesOf(List<String> lines) {
        String from = declaredPackage(lines);
        Set<Edge> found = new TreeSet<>();
        if (from == null) {
            return found;
        }
        for (String line : lines) {
            String to = importedPackage(line);
            if (to != null && !to.equals(from)) {
                found.add(new Edge(from, to));
            }
        }
        return found;
    }

    /** The {@code com.automation} package declared by the file, or {@code null} for anything else. */
    static String declaredPackage(List<String> lines) {
        for (String line : lines) {
            if (!line.startsWith("package ")) {
                continue;
            }
            String declared = strip(line.substring("package ".length()));
            return declared.startsWith(PREFIX) || declared.equals(ROOT_PACKAGE) ? declared : null;
        }
        return null;
    }

    /**
     * The package of the type imported by this line, or {@code null} when the line is not an import
     * of a {@code com.automation} type.
     */
    static String importedPackage(String line) {
        if (!line.startsWith("import ")) {
            return null;
        }
        String imported = strip(line.substring("import ".length()));
        if (imported.startsWith("static ")) {
            imported = strip(imported.substring("static ".length()));
        }
        if (!imported.startsWith(PREFIX)) {
            return null;
        }
        return packageOf(imported);
    }

    /** Longest prefix of lower-case segments – the package part of a fully qualified name. */
    private static String packageOf(String qualifiedName) {
        StringBuilder result = new StringBuilder();
        for (String segment : qualifiedName.split("\\.")) {
            if (segment.isEmpty() || !Character.isLowerCase(segment.charAt(0))) {
                break;
            }
            if (result.length() > 0) {
                result.append('.');
            }
            result.append(segment);
        }
        return result.toString();
    }

    /** Everything up to the terminating semicolon, trimmed. */
    private static String strip(String rest) {
        int semicolon = rest.indexOf(';');
        return (semicolon < 0 ? rest : rest.substring(0, semicolon)).trim();
    }

    private static Path sourceRoot() {
        return SOURCE_ROOTS.stream()
                .filter(Files::isDirectory)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "source import scan unavailable: none of " + SOURCE_ROOTS + " exists below "
                                + Paths.get("").toAbsolutePath() + " – an unmeasured layer rule is not a kept one."));
    }
}
