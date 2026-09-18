package com.automation.architecture;

import com.automation.architecture.PackageEdges.Edge;
import org.junit.jupiter.api.Test;

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
 * <p>Measured from the compiled bytecode via {@link PackageEdges} (jdeps), which also catches the
 * indirect and static ways an import grep misses. Its blind spot is the reverse: a dependency that
 * only appears as a generic type argument is erased and therefore invisible here.
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
