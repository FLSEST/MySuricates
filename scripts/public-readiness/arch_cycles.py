#!/usr/bin/env python3
"""Cycles between packages from an edge list - deterministic, no third-party packages.

Input (stdin or a file as first argument): TSV lines

    from<TAB>to[<TAB>count]

Blank lines and lines starting with '#' are ignored; further columns
are not evaluated.

Output (stably sorted, empty if there are no cycles):

    cycle<TAB><size><TAB><package1,package2,...>  per strongly connected component
                                                  (size > 1 or self edge)
    pair<TAB><a><TAB><b>                          per mutually dependent pair, a < b

The cycles come first (largest first, ties alphabetically by the first member),
then the pairs. The exit code is always 0: the output is a fact sheet for a
downstream evaluator, not a gate.

Used by scripts/public-readiness/arch-metrics.sh, tested by
scripts/tests/test-arch-metrics.sh.
"""

from __future__ import annotations

import sys


def read_edges(lines):
    """Edge list -> sorted, duplicate-free list of (from, to) pairs."""
    edges = set()
    for raw in lines:
        line = raw.rstrip("\n").rstrip("\r")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            parts = line.split()
        if len(parts) < 2:
            continue
        source, target = parts[0].strip(), parts[1].strip()
        if source and target:
            edges.add((source, target))
    return sorted(edges)


def build_graph(edges):
    """(Sorted nodes, adjacency with sorted targets)."""
    adjacency = {}
    nodes = set()
    for source, target in edges:
        nodes.add(source)
        nodes.add(target)
        adjacency.setdefault(source, set()).add(target)
    return sorted(nodes), {n: sorted(t) for n, t in adjacency.items()}


def strongly_connected_components(nodes, adjacency):
    """Tarjan, iterative (no recursion limit). Components sorted internally."""
    index = {}
    lowlink = {}
    on_stack = set()
    stack = []
    components = []
    counter = 0

    for root in nodes:
        if root in index:
            continue
        work = [(root, 0)]
        while work:
            node, next_child = work[-1]
            if next_child == 0:
                index[node] = lowlink[node] = counter
                counter += 1
                stack.append(node)
                on_stack.add(node)
            descended = False
            neighbours = adjacency.get(node, ())
            for position in range(next_child, len(neighbours)):
                neighbour = neighbours[position]
                if neighbour not in index:
                    work[-1] = (node, position + 1)
                    work.append((neighbour, 0))
                    descended = True
                    break
                if neighbour in on_stack:
                    lowlink[node] = min(lowlink[node], index[neighbour])
            if descended:
                continue
            work.pop()
            if lowlink[node] == index[node]:
                component = []
                while True:
                    member = stack.pop()
                    on_stack.discard(member)
                    component.append(member)
                    if member == node:
                        break
                components.append(sorted(component))
            if work:
                parent = work[-1][0]
                lowlink[parent] = min(lowlink[parent], lowlink[node])
    return components


def find_cycles(edges):
    """(Cycles, mutual pairs) from an edge list."""
    nodes, adjacency = build_graph(edges)
    edge_set = set(edges)
    cycles = [
        component
        for component in strongly_connected_components(nodes, adjacency)
        if len(component) > 1 or (component[0], component[0]) in edge_set
    ]
    cycles.sort(key=lambda component: (-len(component), component))
    pairs = sorted(
        (source, target)
        for source, target in edges
        if source < target and (target, source) in edge_set
    )
    return cycles, pairs


def format_report(cycles, pairs):
    lines = ["cycle\t%d\t%s" % (len(c), ",".join(c)) for c in cycles]
    lines += ["pair\t%s\t%s" % (a, b) for a, b in pairs]
    return lines


def main(argv):
    if len(argv) > 1 and argv[1] not in ("-", "--"):
        with open(argv[1], "r", encoding="utf-8", errors="replace") as handle:
            edges = read_edges(handle)
    else:
        edges = read_edges(sys.stdin)
    cycles, pairs = find_cycles(edges)
    for line in format_report(cycles, pairs):
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
