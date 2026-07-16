#!/usr/bin/env python3
"""Backend-Package-Abhängigkeitsgraph (com.automation, Tiefe 2) aus Java-Imports.

Aufruf: extract_backend_deps.py <repo_root> <run_dir>
Output: backend_deps.{dot,svg,edges.json,diff.txt} im run_dir + Diff auf stdout.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common

JAVA_ROOT = os.path.join("ai-automation-backend", "src", "main", "java")
PKG_RE = re.compile(r"^package\s+com\.automation(?:\.([\w.]+))?\s*;", re.M)
IMP_RE = re.compile(r"^import\s+(?:static\s+)?com\.automation\.([\w.]+(?:\.\*)?)\s*;", re.M)

LAYER_COLORS = {
    "controller": "#dbeafe",
    "service": "#dcfce7",
    "repository": "#fef9c3",
    "entity": "#fee2e2",
    "dto": "#f3e8ff",
    "config": "#e2e8f0",
    "mapper": "#ccfbf1",
    "util": "#f1f5f9",
    "exception": "#fde68a",
}
DEFAULT_COLOR = "#f8fafc"


def package_part(segments):
    """Führende lowercase-Segmente = Package; Klassen/Member/Wildcard abschneiden."""
    pkg = []
    for s in segments:
        if s == "*" or (s and s[0].isupper()):
            break
        pkg.append(s)
    return pkg


def node_of(pkg_segments):
    if not pkg_segments:
        return "(root)"
    return ".".join(pkg_segments[:2])


def main():
    repo_root, run_dir = sys.argv[1], sys.argv[2]
    java_root = os.path.join(repo_root, JAVA_ROOT)
    class_count = {}
    edge_weights = {}
    for dirpath, dirnames, filenames in os.walk(java_root):
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            with open(os.path.join(dirpath, fn), encoding="utf-8", errors="replace") as f:
                text = f.read()
            m = PKG_RE.search(text)
            if not m:
                continue
            src = node_of(package_part((m.group(1) or "").split(".")))
            class_count[src] = class_count.get(src, 0) + 1
            targets = set()
            for imp in IMP_RE.findall(text):
                dst = node_of(package_part(imp.split(".")))
                if dst != src:
                    targets.add(dst)
            for dst in targets:
                edge_weights[(src, dst)] = edge_weights.get((src, dst), 0) + 1

    nodes = set(class_count) | {b for _, b in edge_weights}
    lines = [
        "digraph backend_deps {",
        "  rankdir=LR;",
        '  graph [fontname="Helvetica"];',
        '  node [shape=box, style="rounded,filled", fontname="Helvetica", fontsize=11, color="#cbd5e1"];',
        '  edge [color="#94a3b8", arrowsize=0.7];',
    ]
    for n in sorted(nodes):
        color = LAYER_COLORS.get(n.split(".")[0], DEFAULT_COLOR)
        label = "%s\\n(%d Klassen)" % (n, class_count.get(n, 0))
        lines.append("  %s [label=%s, fillcolor=%s];" % (common.q(n), common.q(label), common.q(color)))
    for (a, b), w in sorted(edge_weights.items()):
        penwidth = min(1.0 + 0.5 * (w.bit_length() - 1), 4.0)
        lines.append("  %s -> %s [penwidth=%.1f];" % (common.q(a), common.q(b), penwidth))
    lines.append("}")

    sys.exit(common.emit_graph(repo_root, run_dir, "backend_deps",
                               "\n".join(lines) + "\n", nodes, edge_weights))


if __name__ == "__main__":
    main()
