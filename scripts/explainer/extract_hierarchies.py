#!/usr/bin/env python3
"""Backend-Familien: Interfaces/Basisklassen mit >=3 Implementierungen als kompakte Bäume.

Zeigt die Erweiterungspunkte des Systems (Plugin-Muster: BlockExecutor,
IntegrationProvider, ...) — übersichtlich, eine Cluster-Box je Familie.
Aufruf: extract_hierarchies.py <repo_root> <run_dir>
Output: hierarchies.{dot,svg,edges.json,diff.txt} im run_dir + Diff auf stdout.
"""
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common

JAVA_ROOT = os.path.join("ai-automation-backend", "src", "main", "java")
MIN_CHILDREN = 3
MAX_FAMILIES = 12
TYPE_RE = re.compile(
    r"^\s*(?:public\s+|protected\s+|static\s+|final\s+|sealed\s+)*(abstract\s+)?"
    r"(class|interface|enum|record)\s+(\w+)(?:<[^>{]*>)?"
    r"(?:\s+extends\s+([\w.,<>\s]+?))?(?:\s+implements\s+([\w.,<>\s]+?))?\s*(?:permits[^{]*)?\{",
    re.M)
KIND_COLOR = {"interface": "#c7d2fe", "abstract": "#bae6fd"}
CHILD_COLOR = "#f0fdf4"


def simple_names(clause):
    """'com.foo.Bar<T>, Baz' -> ['Bar', 'Baz'] (Generics/Packages abstreifen)."""
    if not clause:
        return []
    names = []
    for part in re.sub(r"<[^<>]*>", "", clause).split(","):
        part = part.strip().split(".")[-1]
        if part:
            names.append(part)
    return names


def main():
    repo_root, run_dir = sys.argv[1], sys.argv[2]
    java_root = os.path.join(repo_root, JAVA_ROOT)
    kinds = {}      # name -> "interface" | "abstract" | "class" | ...
    parents = {}    # name -> [parent-simple-names]
    for dirpath, _, filenames in os.walk(java_root):
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            with open(os.path.join(dirpath, fn), encoding="utf-8", errors="replace") as f:
                text = f.read()
            for m in TYPE_RE.finditer(text):
                abstract, kind, name = m.group(1), m.group(2), m.group(3)
                kinds.setdefault(name, "abstract" if (abstract and kind == "class") else kind)
                parents.setdefault(name, []).extend(
                    simple_names(m.group(4)) + simple_names(m.group(5)))

    children = {}   # parent -> sorted set of children (nur com.automation-eigene Eltern)
    for child, ps in parents.items():
        for p in ps:
            if p in kinds and p != child:
                children.setdefault(p, set()).add(child)

    families = sorted(
        [(p, sorted(cs)) for p, cs in children.items() if len(cs) >= MIN_CHILDREN],
        key=lambda x: (-len(x[1]), x[0]))
    notes = []
    if len(families) > MAX_FAMILIES:
        dropped = ", ".join("%s (%d)" % (p, len(cs)) for p, cs in families[MAX_FAMILIES:])
        notes.append("weggelassen (Limit %d Familien): %s" % (MAX_FAMILIES, dropped))
        families = families[:MAX_FAMILIES]

    edge_weights = {}
    nodes = set()
    lines = [
        "digraph hierarchies {",
        "  rankdir=BT;",
        "  pack=16;",
        '  packmode="array_u2";',
        '  graph [fontname="Helvetica", fontsize=12];',
        '  node [shape=box, style="rounded,filled", fontname="Helvetica", fontsize=10, color="#cbd5e1"];',
        '  edge [color="#64748b", arrowhead=onormal, arrowsize=0.8];',
    ]
    for idx, (parent, cs) in enumerate(families):
        kind = kinds.get(parent, "class")
        head_color = KIND_COLOR.get(kind, "#e2e8f0")
        # Cluster-Knoten familienweise eindeutig benennen, Klassen können in
        # mehreren Familien auftauchen (z. B. implements A, B)
        lines.append("  subgraph cluster_f%d {" % idx)
        lines.append("    label=%s; style=rounded; color=\"#94a3b8\"; fontsize=11;"
                     % common.q("%s (%d)" % (parent, len(cs))))
        pnode = "f%d.%s" % (idx, parent)
        lines.append("    %s [label=%s, fillcolor=%s, fontsize=11];"
                     % (common.q(pnode), common.q("«%s»\\n%s" % (kind, parent)), common.q(head_color)))
        for c in cs:
            cnode = "f%d.%s" % (idx, c)
            lines.append("    %s [label=%s, fillcolor=%s];"
                         % (common.q(cnode), common.q(c), common.q(CHILD_COLOR)))
            lines.append("    %s -> %s;" % (common.q(cnode), common.q(pnode)))
            edge_weights[(parent, c)] = 1
        lines.append("  }")
        nodes.add(parent)
        nodes.update(cs)
    lines.append("}")

    dot_source = "\n".join(lines) + "\n"
    # unflatten staffelt breite Familien über mehrere Ränge (Seitenverhältnis ~2:1
    # statt 35:1 — sonst quetscht max-width:100% das SVG auf Haarlinien-Höhe)
    try:
        proc = subprocess.run(["unflatten", "-l", "3"], input=dot_source,
                              capture_output=True, text=True, timeout=30)
        if proc.returncode == 0 and proc.stdout.strip():
            dot_source = proc.stdout
    except OSError:
        pass

    sys.exit(common.emit_graph(repo_root, run_dir, "hierarchies",
                               dot_source, nodes, edge_weights, notes))


if __name__ == "__main__":
    main()
