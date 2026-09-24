package com.automation.architecture;

import com.automation.architecture.PackageEdges.Edge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet against package cycles: no new pair of {@code com.automation} packages may depend on each
 * other in both directions. The pairs that exist today are pinned in {@link #ALLOWED_PAIRS}.
 *
 * <p>The list only ever shrinks. Untangling a pair is welcome – remove its entry in the same commit.
 * Adding an entry is not a fix: a new mutual dependency means the new call belongs on the other side
 * of the boundary, behind an interface, or in a third package.
 *
 * <p>Measured from the compiled production classes via {@link PackageEdges}, the same jdeps pipeline
 * that {@code scripts/public-readiness/arch-metrics.sh} reports weekly as {@code cycle_pairs=}. The
 * script additionally reports the strongly connected components; this ratchet deliberately guards
 * only the pairs, because a pair is the smallest unit anybody can actually untangle.
 */
class PackageCycleRatchetTest {

    /**
     * Canonical form {@code "a <-> b"} with {@code a} lexicographically before {@code b}.
     * State on 2026-09-18 (commit 3c984b58): the 13 pairs that arch-metrics.sh reports as
     * {@code cycle_pairs=13}. 2026-09-24: scheduler and dlq no longer pair with service.workflow
     * (SuspendState moved, DLQ entries via WorkflowRunCompletedEvent) — 11 pairs. Then service.system
     * no longer calls the caches of ai and workflow on a settings change (AppSettingChangedEvent) — 9.
     * AssistantFormatUtils moved to its own leaf package service.assistant.format — 7.
     */
    static final Set<String> ALLOWED_PAIRS = Set.of(
            "com.automation.config <-> com.automation.security",
            "com.automation.config <-> com.automation.service.project",
            "com.automation.dto <-> com.automation.service.assistant",
            "com.automation.executor <-> com.automation.service.cli",
            "com.automation.service.ai <-> com.automation.service.cli",
            "com.automation.service.project <-> com.automation.service.workflow",
            "com.automation.service.todo <-> com.automation.service.workflow");

    @Test
    void noNewMutualPackageDependencies() {
        Set<String> actual = mutualPairs(PackageEdges.ofProductionClassesOrSkip());

        List<String> added = actual.stream().filter(pair -> !ALLOWED_PAIRS.contains(pair)).toList();

        assertThat(added).as(
                        "New mutual package dependencies (both directions between the same two packages). "
                                + "Do not extend ALLOWED_PAIRS – move the call instead: %s", added)
                .isEmpty();
    }

    /**
     * A resolved pair is good news, so it must not break the build – but the stale entry has to go,
     * otherwise the ratchet quietly re-permits the cycle later. Hence a hint on stdout, not a failure.
     */
    @Test
    void resolvedPairsAreReportedAsAHint() {
        Set<String> actual = mutualPairs(PackageEdges.ofProductionClassesOrSkip());

        List<String> resolved = ALLOWED_PAIRS.stream().filter(pair -> !actual.contains(pair)).sorted().toList();

        if (!resolved.isEmpty()) {
            System.out.println("[PackageCycleRatchet] no longer mutual – shorten ALLOWED_PAIRS ("
                    + resolved.size() + "): " + String.join(", ", resolved));
        }
    }

    @Test
    void allowlistEntriesAreCanonical() {
        List<String> malformed = new ArrayList<>();
        for (String pair : ALLOWED_PAIRS) {
            String[] sides = pair.split(" <-> ", -1);
            if (sides.length != 2
                    || sides[0].compareTo(sides[1]) >= 0
                    || !sides[0].startsWith(PackageEdges.ROOT_PACKAGE)
                    || !sides[1].startsWith(PackageEdges.ROOT_PACKAGE)) {
                malformed.add(pair);
            }
        }
        assertThat(malformed).as("allowlist entries must read \"<package a> <-> <package b>\" with a < b")
                .isEmpty();
    }

    @Test
    void mutualPairsFindBothDirectionsOnly() {
        Set<Edge> edges = Set.of(
                new Edge("com.automation.b", "com.automation.a"),
                new Edge("com.automation.a", "com.automation.b"),
                new Edge("com.automation.a", "com.automation.c"));

        assertThat(mutualPairs(edges)).containsExactly("com.automation.a <-> com.automation.b");
    }

    /** Pairs with edges in both directions, canonical and sorted. */
    static Set<String> mutualPairs(Collection<Edge> edges) {
        Set<String> directed = new TreeSet<>();
        edges.forEach(edge -> directed.add(edge.from() + " -> " + edge.to()));

        Set<String> pairs = new TreeSet<>();
        for (Edge edge : edges) {
            if (edge.from().compareTo(edge.to()) < 0
                    && directed.contains(edge.to() + " -> " + edge.from())) {
                pairs.add(edge.from() + " <-> " + edge.to());
            }
        }
        return pairs;
    }
}
