package com.automation.architecture;

import com.automation.architecture.PackageEdges.Edge;
import com.automation.architecture.SourceImportEdges.Scan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer rule for the backend: calls run controller -> service -> repository, never against that
 * direction. Three edge classes are forbidden between {@code com.automation} packages:
 *
 * <ul>
 *   <li>{@code service.* -> controller.*} – a service must not reach back into the web layer.</li>
 *   <li>{@code controller.* -> repository.*} – controllers go through a service, so that
 *       transactions and lazy associations stay inside {@code @Transactional} (OSIV is off).</li>
 *   <li>{@code repository.* -> service.*} – repositories stay dumb data access.</li>
 * </ul>
 *
 * <p>A ratchet, not a ban from day one: violations that exist today would be pinned in
 * {@link #ALLOWED_VIOLATIONS} – today that list is empty, so any violation fails the build.
 *
 * <p>Measured through two independent views, because neither one sees everything:
 *
 * <ul>
 *   <li>{@link PackageEdges} reads the compiled bytecode with jdeps and catches the indirect and
 *       static ways an import grep misses. Blind spot: a dependency that only appears as a generic
 *       type argument is erased and therefore invisible.</li>
 *   <li>{@link SourceImportEdges} reads the {@code import} lines of the sources and catches exactly
 *       that erased case. Blind spot: fully qualified names without an import, inheritance,
 *       reflection, generated code.</li>
 * </ul>
 *
 * <p>Both views run against the same {@link #FORBIDDEN} rules but keep their own allowlist, so a
 * pinned violation in one view never silently covers the other. They are complementary, not a
 * complete net: a fully qualified name inside a generic type argument
 * ({@code Page<com.automation.repository.Foo>} without an import) is erased in the bytecode and has
 * no import line – neither view sees it.
 */
class LayerRuleTest {

    static final List<LayerRule> FORBIDDEN = List.of(
            new LayerRule("com.automation.service", "com.automation.controller"),
            new LayerRule("com.automation.controller", "com.automation.repository"),
            new LayerRule("com.automation.repository", "com.automation.service"));

    /**
     * Canonical form {@code "<from package> -> <to package>"}; only ever shrinks. Empty on
     * 2026-09-18 (commit 3c984b58): the compiled code holds none of the three forbidden edge
     * classes, so the ratchet starts as a plain ban.
     */
    static final Set<String> ALLOWED_VIOLATIONS = Set.of();

    /**
     * Same canonical form, but for the import view – kept separate from {@link #ALLOWED_VIOLATIONS}
     * on purpose. Empty since {@code DashboardLayoutVersionListItem} moved from
     * {@code com.automation.repository} to {@code com.automation.dto.response}: that projection was
     * the one edge only the import scan could see, because the controller touches it solely as
     * {@code Page<DashboardLayoutVersionListItem>}.
     */
    static final Set<String> ALLOWED_SOURCE_VIOLATIONS = Set.of();

    /**
     * A scan that reads no sources would report every rule as kept. 100 is far below the ~770 files
     * of this module and far above anything a broken root would produce.
     */
    static final int MIN_SCANNED_CLASSES = 100;

    record LayerRule(String from, String to) {

        boolean matches(Edge edge) {
            return PackageEdges.inLayer(edge.from(), from) && PackageEdges.inLayer(edge.to(), to);
        }

        @Override
        public String toString() {
            return from + ".* -> " + to + ".*";
        }
    }

    @Test
    void noForbiddenLayerEdgesOutsideTheAllowlist() {
        Set<String> actual = violations(PackageEdges.ofProductionClassesOrSkip());

        List<String> added = actual.stream().filter(edge -> !ALLOWED_VIOLATIONS.contains(edge)).toList();

        assertThat(added).as("Forbidden layer dependencies %s. Do not extend ALLOWED_VIOLATIONS – "
                + "route the call through the layer below: %s", FORBIDDEN, added).isEmpty();
    }

    @Test
    void allowlistHoldsNoResolvedViolations() {
        Set<String> actual = violations(PackageEdges.ofProductionClassesOrSkip());

        List<String> resolved = ALLOWED_VIOLATIONS.stream().filter(edge -> !actual.contains(edge)).sorted().toList();

        assertThat(resolved).as("no longer a violation – remove from ALLOWED_VIOLATIONS, otherwise the "
                + "ratchet silently re-permits it").isEmpty();
    }

    @Test
    void noForbiddenLayerImportsOutsideTheAllowlist() throws IOException {
        Scan scan = SourceImportEdges.ofProductionSources();

        assertThat(scan.classes()).as("import scan read %d source files – below %d that is a broken "
                + "measurement, not a clean architecture", scan.classes(), MIN_SCANNED_CLASSES)
                .isGreaterThan(MIN_SCANNED_CLASSES);
        if (scan.indentedImports() > 0) {
            System.out.println("[LayerRuleTest] " + scan.indentedImports() + " indented import line(s) "
                    + "were skipped by the column-0 rule of SourceImportEdges – if those are real "
                    + "imports, this view no longer sees them.");
        }

        List<String> added = violations(scan.edges()).stream()
                .filter(edge -> !ALLOWED_SOURCE_VIOLATIONS.contains(edge)).toList();

        assertThat(added).as("Forbidden layer imports %s. Do not extend ALLOWED_SOURCE_VIOLATIONS – "
                + "move the shared type into com.automation.dto.response or route the call through "
                + "the layer below: %s", FORBIDDEN, added).isEmpty();
    }

    @Test
    void sourceAllowlistHoldsNoResolvedViolations() throws IOException {
        Set<String> actual = violations(SourceImportEdges.ofProductionSources().edges());

        List<String> resolved = ALLOWED_SOURCE_VIOLATIONS.stream()
                .filter(edge -> !actual.contains(edge)).sorted().toList();

        assertThat(resolved).as("no longer a violation – remove from ALLOWED_SOURCE_VIOLATIONS, "
                + "otherwise the ratchet silently re-permits it").isEmpty();
    }

    @Test
    void importScanSeesTheGenericTypeArgumentThatJdepsErases() {
        List<String> source = List.of(
                "package com.automation.controller;",
                "",
                "import com.automation.repository.SomeProjection;",
                "import static com.automation.repository.Queries.all;",
                "import org.springframework.data.domain.Page;",
                "",
                "class C {",
                "    // import com.automation.repository.NotAnImport; (a comment, never counted)",
                "    import com.automation.repository.Indented;",
                "    Page<SomeProjection> list() { return null; }",
                "}");

        assertThat(violations(SourceImportEdges.edgesOf(source)))
                .containsExactly("com.automation.controller -> com.automation.repository");
        assertThat(SourceImportEdges.countIndentedImports(source))
                .as("an indented import is skipped by the column-0 rule – but counted, so the gap is visible")
                .isEqualTo(1);
    }

    @Test
    void violationsCoverSubPackagesAndNothingElse() {
        Set<Edge> edges = Set.of(
                new Edge("com.automation.service.workflow", "com.automation.controller"),
                new Edge("com.automation.controller", "com.automation.repository.projection"),
                new Edge("com.automation.controller", "com.automation.dto"),
                new Edge("com.automation.serviceless", "com.automation.controller"));

        assertThat(violations(edges)).containsExactly(
                "com.automation.controller -> com.automation.repository.projection",
                "com.automation.service.workflow -> com.automation.controller");
    }

    /** Forbidden edges in canonical form, sorted. */
    static Set<String> violations(Collection<Edge> edges) {
        Set<String> found = new TreeSet<>();
        for (Edge edge : edges) {
            if (FORBIDDEN.stream().anyMatch(rule -> rule.matches(edge))) {
                found.add(edge.toString());
            }
        }
        return found;
    }
}
