package com.automation.architecture;

import com.automation.architecture.PackageEdges.Edge;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ratchet against cycles between {@code com.automation} packages, on two levels.
 *
 * <p><b>Pairs:</b> no new pair of packages may depend on each other in both directions. The pairs
 * that exist today are pinned in {@link #ALLOWED_PAIRS}.
 *
 * <p><b>Components:</b> no strongly connected component of the package graph – packages that all
 * reach each other through some chain of dependencies – may grow. The largest one is capped at
 * {@link #MAX_CYCLE_COMPONENT_SIZE}, and the members of every component with more than two packages
 * are pinned in {@link #CYCLE_COMPONENTS}. Pairs alone cannot see this: a single new edge
 * {@code service.workflow -> service.goal} once pulled seven packages into the large component
 * (17 to 24) without adding a mutual pair, and every test stayed green.
 *
 * <p>Both only ever shrink. Untangling is welcome – update the pinned values in the same commit.
 * Extending them is not a fix: a new dependency back into a cycle means the new call belongs on the
 * other side of the boundary, behind an interface, or in a third package.
 *
 * <p>Measured from the compiled production classes via {@link PackageEdges}, the same jdeps pipeline
 * that {@code scripts/public-readiness/arch-metrics.sh} reports weekly: {@code cycle_pairs=} for the
 * pairs, the {@code cycle <size> <packages>} lines of {@code ## cycles} for the components (Tarjan
 * there as well as here).
 */
class PackageCycleRatchetTest {

    /**
     * Canonical form {@code "a <-> b"} with {@code a} lexicographically before {@code b}.
     * State on 2026-09-18 (commit 3c984b58): the 13 pairs that arch-metrics.sh reports as
     * {@code cycle_pairs=13}. 2026-09-24: scheduler and dlq no longer pair with service.workflow
     * (SuspendState moved, DLQ entries via WorkflowRunCompletedEvent) — 11 pairs. Then service.system
     * no longer calls the caches of ai and workflow on a settings change (AppSettingChangedEvent) — 9.
     * AssistantFormatUtils moved to its own leaf package service.assistant.format — 7.
     * The run-output parsers left service.todo for the leaf package service.runoutput — 6.
     * PendingTool moved to com.automation.dto, next to the chat response that carries it — 5.
     * RateLimitConfig moved to com.automation.security, next to the RateLimitFilter that reads it — 4.
     * OnboardingProfileSeeder moved to service.project, next to the services it seeds for — 3.
     * The HostExecutionRequest overload of StepAiMetadata.aiRequest moved to service.cli — 2.
     * 2026-09-26: the Claude CLI output classes left service.cli for the leaf package
     * service.cli.claude, which ai and cli both read — 1. ProjectWorkflowsChangedEvent moved to
     * com.automation.event and OnboardingRenderer reads the workflows through WorkflowRepository — 0.
     */
    static final Set<String> ALLOWED_PAIRS = Set.of();

    /**
     * Size of the largest strongly connected component – the first {@code cycle} line of
     * arch-metrics.sh. 17 on 2026-09-25 (commit c0d5666c): the audit series (TODO fc149510) had
     * brought it down to 17, the edge {@code service.workflow -> service.goal} then grew it to 24
     * unnoticed, and d2844a58 took that edge out again. 2026-10-06: BlockSpecs left
     * service.assistant.fetch for the leaf package executor.spec, so service.workflow no longer reaches
     * the fetch package, and service.pool, which only came back through it, left as well – 15 (TODO b7c56046).
     */
    static final int MAX_CYCLE_COMPONENT_SIZE = 15;

    /**
     * The members of every strongly connected component with more than two packages, largest first,
     * measured on 2026-09-25 (commit c0d5666c). 2026-09-26: the four packages around modules
     * (dto.response, modules, repository, service.activitylog) no longer form a cycle – the module
     * mapping left the response records for mapper.ModuleCapabilityMapper. A failure names the
     * packages that are new against this reference. Components of exactly two packages are mutual pairs, see {@link #ALLOWED_PAIRS}.
     * Renaming or moving a listed package means updating its entry in the same commit.
     */
    static final List<Set<String>> CYCLE_COMPONENTS = List.of(
            Set.of(
                    "com.automation.config",
                    "com.automation.executor",
                    "com.automation.metrics",
                    "com.automation.security",
                    "com.automation.service.ai",
                    "com.automation.service.ai.catalog",
                    "com.automation.service.cli",
                    "com.automation.service.dashboard",
                    "com.automation.service.memory",
                    "com.automation.service.metrics",
                    "com.automation.service.project",
                    "com.automation.service.system",
                    "com.automation.service.system.settings",
                    "com.automation.service.workflow",
                    "com.automation.websocket"));

    /** Same order as arch_cycles.py: largest first, then by first member (components are disjoint). */
    private static final Comparator<SortedSet<String>> LARGEST_FIRST =
            Comparator.<SortedSet<String>>comparingInt(Set::size).reversed().thenComparing(SortedSet::first);

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

    @Test
    void noCycleComponentGrows() {
        List<String> grown = grownComponents(
                PackageEdges.ofProductionClassesOrSkip(), CYCLE_COMPONENTS, MAX_CYCLE_COMPONENT_SIZE);

        assertThat(grown).withFailMessage("Package cycles grew. Do not extend CYCLE_COMPONENTS or raise "
                        + "MAX_CYCLE_COMPONENT_SIZE – take out the edge that pulls a package in (move the "
                        + "call, put it behind an interface or into a third package):%n%s",
                        String.join("\n", grown))
                .isEmpty();
    }

    /**
     * Unlike a resolved pair (only a hint), a shrunk component fails the build: a stale cap would
     * re-permit growth by any package over any edge – exactly what this ratchet exists to catch – so
     * the pinned values follow every shrink in the commit that earned it.
     */
    @Test
    void shrunkCycleComponentsLowerTheRatchet() {
        List<String> stale = staleComponents(
                PackageEdges.ofProductionClassesOrSkip(), CYCLE_COMPONENTS, MAX_CYCLE_COMPONENT_SIZE);

        assertThat(stale).withFailMessage("Package cycles shrank – ratchet down in the same commit, "
                        + "otherwise the old size stays silently permitted:%n%s", String.join("\n", stale))
                .isEmpty();
    }

    @Test
    void pinnedCycleComponentsAreCanonical() {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Set<String> component : CYCLE_COMPONENTS) {
            if (component.size() <= 2) {
                problems.add(new TreeSet<>(component) + " has fewer than three packages – pairs belong in "
                        + "ALLOWED_PAIRS");
            }
            component.stream().filter(member -> !seen.add(member)).sorted()
                    .forEach(member -> problems.add(member + " is pinned in two components"));
        }
        int largestPinned = CYCLE_COMPONENTS.stream().mapToInt(Set::size).max().orElse(0);
        if (largestPinned > 0 && largestPinned != MAX_CYCLE_COMPONENT_SIZE) {
            problems.add("MAX_CYCLE_COMPONENT_SIZE is " + MAX_CYCLE_COMPONENT_SIZE
                    + ", the largest pinned component has " + largestPinned + " packages");
        }
        assertThat(problems).as("CYCLE_COMPONENTS must hold disjoint components of more than two "
                + "packages, the largest one exactly MAX_CYCLE_COMPONENT_SIZE big").isEmpty();
    }

    @Test
    void cycleComponentsSeeWhatThePairsMiss() {
        Set<Edge> edges = Set.of(
                new Edge("a", "b"), new Edge("b", "c"), new Edge("c", "a"),
                new Edge("c", "d"),
                new Edge("e", "f"), new Edge("f", "e"));

        assertThat(mutualPairs(edges)).as("the triangle a, b, c has no mutual pair")
                .containsExactly("e <-> f");
        assertThat(cycleComponents(edges)).as("d only hangs off the triangle")
                .containsExactly(new TreeSet<>(Set.of("a", "b", "c")), new TreeSet<>(Set.of("e", "f")));
    }

    @Test
    void oneNewEdgeIntoACycleNamesTheNewcomersAndTheEdgesThatTieThemIn() {
        List<Set<String>> pinned = List.of(Set.of("a", "b", "c"));
        List<Edge> before = List.of(
                new Edge("a", "b"), new Edge("b", "c"), new Edge("c", "a"), new Edge("x", "a"));
        List<Edge> after = new ArrayList<>(before);
        after.add(new Edge("b", "x"));

        assertThat(grownComponents(before, pinned, 3)).isEmpty();
        List<String> grown = grownComponents(after, pinned, 3);
        assertThat(grown).hasSize(1);
        assertThat(grown.get(0))
                .contains("cycle of 4 packages (MAX_CYCLE_COMPONENT_SIZE is 3): [a, b, c, x]",
                        "new against CYCLE_COMPONENTS: [x]",
                        "edges into them: [b -> x]", "edges from them: [x -> a]");
        assertThat(staleComponents(after, pinned, 3)).as("growth is not staleness").isEmpty();
    }

    @Test
    void aSwapAtTheSameSizeIsGrowthAndStalenessAtOnce() {
        List<Set<String>> pinned = List.of(Set.of("a", "b", "c", "d"));
        List<Edge> swapped = List.of(
                new Edge("a", "b"), new Edge("b", "c"), new Edge("c", "e"), new Edge("e", "a"),
                new Edge("d", "a"));

        List<String> grown = grownComponents(swapped, pinned, 4);
        assertThat(grown).hasSize(1);
        assertThat(grown.get(0)).as("same size, so no word about the cap")
                .startsWith("cycle of 4 packages: [a, b, c, e]")
                .contains("new against CYCLE_COMPONENTS: [e]",
                        "edges into them: [c -> e]", "edges from them: [e -> a]");
        assertThat(staleComponents(swapped, pinned, 4)).containsExactly(
                "pinned cycle of 4 packages lost [d] – take them out of its CYCLE_COMPONENTS entry");
    }

    @Test
    void aSplitOrShrunkComponentAsksToRatchetDown() {
        List<Set<String>> pinned = List.of(Set.of("a", "b", "c", "d", "e", "f", "g"), Set.of("x", "y", "z"));
        List<Edge> split = List.of(
                new Edge("a", "b"), new Edge("b", "c"), new Edge("c", "a"), new Edge("c", "d"),
                new Edge("d", "e"), new Edge("e", "f"), new Edge("f", "d"), new Edge("g", "a"),
                new Edge("x", "y"), new Edge("y", "x"), new Edge("y", "z"));

        assertThat(grownComponents(split, pinned, 7)).as("splitting or shrinking is no growth").isEmpty();
        assertThat(staleComponents(split, pinned, 7)).containsExactly(
                "largest cycle down to 3 packages – lower MAX_CYCLE_COMPONENT_SIZE from 7 to 3",
                "pinned cycle of 7 packages split into [[a, b, c], [d, e, f]], lost [g] – replace its "
                        + "CYCLE_COMPONENTS entry with the parts",
                "pinned cycle [x, y, z] dissolved – remove its CYCLE_COMPONENTS entry");
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

    /**
     * Strongly connected components of at least two packages, largest first – the {@code cycle} lines
     * of arch_cycles.py. {@link PackageEdges} drops self edges, so a single package is never a cycle.
     */
    static List<SortedSet<String>> cycleComponents(Collection<Edge> edges) {
        Map<String, SortedSet<String>> successors = new TreeMap<>();
        for (Edge edge : edges) {
            successors.computeIfAbsent(edge.from(), key -> new TreeSet<>()).add(edge.to());
            successors.computeIfAbsent(edge.to(), key -> new TreeSet<>());
        }
        return Tarjan.componentsOf(successors).stream()
                .filter(component -> component.size() > 1)
                .sorted(LARGEST_FIRST)
                .toList();
    }

    /**
     * One entry per component that grew: bigger than {@code maxSize}, or – with more than two packages –
     * holding packages outside the pinned component it overlaps most (all of them for a new cycle).
     * Two-package components are mutual pairs and left to {@link #noNewMutualPackageDependencies()}.
     */
    static List<String> grownComponents(Collection<Edge> edges, List<Set<String>> pinned, int maxSize) {
        List<String> grown = new ArrayList<>();
        for (SortedSet<String> component : cycleComponents(edges)) {
            SortedSet<String> newcomers =
                    component.size() > 2 ? outsidePinned(component, pinned) : new TreeSet<>();
            if (component.size() <= maxSize && newcomers.isEmpty()) {
                continue;
            }
            StringBuilder entry = new StringBuilder("cycle of " + component.size() + " packages");
            if (component.size() > maxSize) {
                entry.append(" (MAX_CYCLE_COMPONENT_SIZE is ").append(maxSize).append(')');
            }
            entry.append(": ").append(component);
            if (!newcomers.isEmpty()) {
                entry.append("\n    new against CYCLE_COMPONENTS: ").append(newcomers);
                List<Edge> into = tyingEdges(component, edges,
                        edge -> !newcomers.contains(edge.from()) && newcomers.contains(edge.to()));
                if (!into.isEmpty()) {
                    entry.append("\n    edges into them: ").append(into);
                }
                entry.append("\n    edges from them: ")
                        .append(tyingEdges(component, edges, edge -> newcomers.contains(edge.from())));
            }
            grown.add(entry.toString());
        }
        return grown;
    }

    /**
     * One entry per pinned value the measurement undercuts: a cap above the largest component, and
     * every pinned component that no longer sits whole inside one measured component. Only pinned
     * packages are reported, so the advice is always to take out, never to add.
     */
    static List<String> staleComponents(Collection<Edge> edges, List<Set<String>> pinned, int maxSize) {
        List<SortedSet<String>> components = cycleComponents(edges);
        List<String> stale = new ArrayList<>();
        int largest = components.isEmpty() ? 0 : components.get(0).size();
        if (largest < maxSize) {
            stale.add("largest cycle down to " + largest + " packages – lower MAX_CYCLE_COMPONENT_SIZE from "
                    + maxSize + " to " + largest);
        }
        for (Set<String> reference : pinned) {
            List<SortedSet<String>> parts = new ArrayList<>();
            for (SortedSet<String> component : components) {
                SortedSet<String> part = new TreeSet<>(component);
                part.retainAll(reference);
                if (component.size() > 2 && !part.isEmpty()) {
                    parts.add(part);
                }
            }
            SortedSet<String> lost = new TreeSet<>(reference);
            parts.forEach(lost::removeAll);
            if (parts.isEmpty()) {
                stale.add("pinned cycle " + new TreeSet<>(reference) + " dissolved – remove its "
                        + "CYCLE_COMPONENTS entry");
            } else if (parts.size() > 1) {
                stale.add("pinned cycle of " + reference.size() + " packages split into " + parts
                        + (lost.isEmpty() ? "" : ", lost " + lost)
                        + " – replace its CYCLE_COMPONENTS entry with the parts");
            } else if (!lost.isEmpty()) {
                stale.add("pinned cycle of " + reference.size() + " packages lost " + lost
                        + " – take them out of its CYCLE_COMPONENTS entry");
            }
        }
        return stale;
    }

    /** Members outside the pinned component that overlaps {@code component} most – all, if none does. */
    private static SortedSet<String> outsidePinned(Set<String> component, List<Set<String>> pinned) {
        Set<String> reference = pinned.stream()
                .max(Comparator.comparingLong(
                        candidate -> candidate.stream().filter(component::contains).count()))
                .orElse(Set.of());
        SortedSet<String> outside = new TreeSet<>(component);
        outside.removeAll(reference);
        return outside;
    }

    /**
     * The edges inside {@code component} that match, sorted. Split by direction, because the edge that
     * pulled the newcomers in is usually the only one leading into them from the old cycle – they
     * reached the cycle before, the cycle did not reach them.
     */
    private static List<Edge> tyingEdges(
            Set<String> component, Collection<Edge> edges, Predicate<Edge> matching) {
        return edges.stream()
                .filter(edge -> component.contains(edge.from()) && component.contains(edge.to()))
                .filter(matching)
                .sorted()
                .toList();
    }

    /** Tarjan's algorithm, recursive: the depth is bounded by the package count, far below any stack limit. */
    private static final class Tarjan {

        private final Map<String, SortedSet<String>> successors;
        private final Map<String, Integer> index = new HashMap<>();
        private final Map<String, Integer> lowLink = new HashMap<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> onStack = new HashSet<>();
        private final List<SortedSet<String>> components = new ArrayList<>();

        private Tarjan(Map<String, SortedSet<String>> successors) {
            this.successors = successors;
        }

        /** Every component, single nodes included; {@code successors} has each node as a key. */
        static List<SortedSet<String>> componentsOf(Map<String, SortedSet<String>> successors) {
            Tarjan tarjan = new Tarjan(successors);
            for (String node : successors.keySet()) {
                if (!tarjan.index.containsKey(node)) {
                    tarjan.visit(node);
                }
            }
            return tarjan.components;
        }

        private void visit(String node) {
            int nodeIndex = index.size();
            index.put(node, nodeIndex);
            lowLink.put(node, nodeIndex);
            stack.push(node);
            onStack.add(node);
            for (String next : successors.get(node)) {
                if (!index.containsKey(next)) {
                    visit(next);
                    lowLink.merge(node, lowLink.get(next), Math::min);
                } else if (onStack.contains(next)) {
                    lowLink.merge(node, index.get(next), Math::min);
                }
            }
            if (lowLink.get(node) == nodeIndex) {
                SortedSet<String> component = new TreeSet<>();
                String member;
                do {
                    member = stack.pop();
                    onStack.remove(member);
                    component.add(member);
                } while (!member.equals(node));
                components.add(component);
            }
        }
    }
}
