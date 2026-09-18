#!/usr/bin/env python3
"""Zyklen zwischen Paketen aus einer Kantenliste — deterministisch, ohne Fremdpakete.

Eingabe (stdin oder Datei als erstes Argument): TSV-Zeilen

    von<TAB>nach[<TAB>anzahl]

Leerzeilen und Zeilen, die mit '#' beginnen, werden ignoriert; weitere Spalten
werden nicht ausgewertet.

Ausgabe (stabil sortiert, leer wenn es keine Zyklen gibt):

    cycle<TAB><groesse><TAB><paket1,paket2,...>   je stark zusammenhaengender Komponente
                                                  (Groesse > 1 oder Selbstkante)
    pair<TAB><a><TAB><b>                          je wechselseitig abhaengigem Paar, a < b

Die Zyklen kommen zuerst (groesste zuerst, bei gleicher Groesse alphabetisch nach
dem ersten Mitglied), danach die Paare. Der Exit-Code ist immer 0: die Ausgabe ist
ein Faktenblatt fuer einen nachgelagerten Auswerter, kein Gate.

Verwendet von scripts/public-readiness/arch-metrics.sh, getestet von
scripts/tests/test-arch-metrics.sh.
"""

from __future__ import annotations

import sys


def read_edges(lines):
    """Kantenliste -> sortierte, doppelfreie Liste von (von, nach)-Paaren."""
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
    """(Knoten sortiert, Adjazenz mit sortierten Zielen)."""
    adjacency = {}
    nodes = set()
    for source, target in edges:
        nodes.add(source)
        nodes.add(target)
        adjacency.setdefault(source, set()).add(target)
    return sorted(nodes), {n: sorted(t) for n, t in adjacency.items()}


def strongly_connected_components(nodes, adjacency):
    """Tarjan, iterativ (keine Rekursionsgrenze). Komponenten intern sortiert."""
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
    """(Zyklen, wechselseitige Paare) aus einer Kantenliste."""
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
