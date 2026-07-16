#!/usr/bin/env python3
"""Frontend-Importstruktur: pages/hooks -> hooks/api/stores als Cluster-Graph.

Aufruf: extract_frontend_deps.py <repo_root> <run_dir>
Output: frontend_deps.{dot,svg,edges.json,diff.txt} im run_dir + Diff auf stdout.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common

SRC_ROOT = os.path.join("ai-automation-frontend", "src")
SCAN_DIRS = ("pages", "hooks", "api", "stores")
SRC_CATS = {"pages", "hooks"}
DST_CATS = {"hooks", "api", "stores"}
MAX_EDGES = 350
IMPORT_RE = re.compile(r"""(?:^|\n)\s*(?:import|export)\s+(?:[^;'"]*?\bfrom\s+)?['"]([^'"]+)['"]""")
CLUSTER_STYLE = {
    "pages": ("Pages", "#dbeafe"),
    "hooks": ("Hooks", "#dcfce7"),
    "data": ("API & Stores", "#f3e8ff"),
}


def module_id(rel_path):
    """Pfad relativ src/ ohne Endung, /index normalisiert."""
    mod = re.sub(r"\.(tsx?|jsx?)$", "", rel_path.replace(os.sep, "/"))
    if mod.endswith("/index"):
        mod = mod[: -len("/index")]
    return mod


def resolve(spec, src_rel_dir):
    if spec.startswith("@/"):
        target = spec[2:]
    elif spec.startswith("."):
        target = os.path.normpath(os.path.join(src_rel_dir, spec)).replace(os.sep, "/")
    else:
        return None
    if target.endswith(".css"):
        return None
    return module_id(target)


def main():
    repo_root, run_dir = sys.argv[1], sys.argv[2]
    src_root = os.path.join(repo_root, SRC_ROOT)
    edge_weights = {}
    for cat in SCAN_DIRS:
        base = os.path.join(src_root, cat)
        if not os.path.isdir(base):
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = [d for d in dirnames if d != "__tests__"]
            for fn in filenames:
                if not fn.endswith((".ts", ".tsx")) or ".test." in fn:
                    continue
                rel = os.path.relpath(os.path.join(dirpath, fn), src_root)
                src = module_id(rel)
                src_cat = src.split("/")[0]
                if src_cat not in SRC_CATS:
                    continue
                with open(os.path.join(dirpath, fn), encoding="utf-8", errors="replace") as f:
                    text = f.read()
                src_rel_dir = os.path.dirname(rel)
                for spec in IMPORT_RE.findall(text):
                    dst = resolve(spec, src_rel_dir)
                    if not dst or dst == src:
                        continue
                    if dst.split("/")[0] not in DST_CATS:
                        continue
                    edge_weights[(src, dst)] = edge_weights.get((src, dst), 0) + 1

    notes = []
    if len(edge_weights) > MAX_EDGES:
        before = len(edge_weights)
        edge_weights = {(a, b): w for (a, b), w in edge_weights.items()
                        if not (a.split("/")[0] == "hooks" and b.split("/")[0] == "hooks")}
        notes.append("hooks->hooks-Kanten weggelassen (%d -> %d Kanten, Limit %d)"
                     % (before, len(edge_weights), MAX_EDGES))

    nodes = {a for a, _ in edge_weights} | {b for _, b in edge_weights}
    clusters = {"pages": [], "hooks": [], "data": []}
    for n in sorted(nodes):
        cat = n.split("/")[0]
        clusters[cat if cat in ("pages", "hooks") else "data"].append(n)

    lines = [
        "digraph frontend_deps {",
        "  rankdir=LR;",
        '  graph [fontname="Helvetica", fontsize=12];',
        '  node [shape=box, style="rounded,filled", fontname="Helvetica", fontsize=10, color="#cbd5e1"];',
        '  edge [color="#94a3b8", arrowsize=0.6];',
    ]
    for key in ("pages", "hooks", "data"):
        label, color = CLUSTER_STYLE[key]
        lines.append("  subgraph cluster_%s {" % key)
        lines.append("    label=%s; style=rounded; color=\"#94a3b8\";" % common.q(label))
        for n in clusters[key]:
            short = n.split("/", 1)[1] if "/" in n else n
            lines.append("    %s [label=%s, fillcolor=%s];"
                         % (common.q(n), common.q(short), common.q(color)))
        lines.append("  }")
    for (a, b), _ in sorted(edge_weights.items()):
        lines.append("  %s -> %s;" % (common.q(a), common.q(b)))
    lines.append("}")

    sys.exit(common.emit_graph(repo_root, run_dir, "frontend_deps",
                               "\n".join(lines) + "\n", nodes, edge_weights, notes))


if __name__ == "__main__":
    main()
