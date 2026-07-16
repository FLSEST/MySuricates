#!/usr/bin/env python3
"""Gemeinsame Helfer für die Code-Explainer-Skripte (nur stdlib).

Edge-JSON-Format (explainer/graphs/<key>.edges.json bzw. <run_dir>/<key>.edges.json):
    {"generated": "...", "sha": "...", "nodes": ["a", ...], "edges": [["a", "b", 3], ...]}
"""
import json
import os
import subprocess
from datetime import datetime, timezone


def head_sha(repo_root):
    return subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=repo_root,
        capture_output=True, text=True, check=True,
    ).stdout.strip()


def q(s):
    """Dot-Quoting für Knotennamen/Labels."""
    return '"%s"' % str(s).replace("\\", "\\\\").replace('"', '\\"')


def render_svg(dot_source, out_path):
    """(ok, fehler) — rendert dot-Quelltext nach SVG via Graphviz."""
    try:
        proc = subprocess.run(
            ["dot", "-Tsvg", "-o", out_path],
            input=dot_source, capture_output=True, text=True, timeout=60,
        )
    except (OSError, subprocess.TimeoutExpired) as e:
        return False, str(e)
    if proc.returncode != 0:
        return False, proc.stderr.strip()[:500]
    return True, ""


def load_edges(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def diff_text(label, old, nodes, edges):
    """Menschentext-Diff gegen den letzten Lauf; old darf None sein (Basislauf)."""
    lines = ["@@STRUKTUR-DIFF %s" % label]
    if old is None:
        lines.append("(Basislauf — kein Vergleich möglich)")
        lines.append("= %d Knoten, %d Kanten" % (len(nodes), len(edges)))
        return "\n".join(lines)
    old_nodes = set(old.get("nodes", []))
    old_edges = {(a, b): w for a, b, w in old.get("edges", [])}
    new_nodes = set(nodes)
    new_edges = {(a, b): w for a, b, w in edges}
    changes = 0
    for n in sorted(new_nodes - old_nodes):
        lines.append("+ Knoten: %s" % n)
        changes += 1
    for n in sorted(old_nodes - new_nodes):
        lines.append("- Knoten: %s" % n)
        changes += 1
    for a, b in sorted(set(new_edges) - set(old_edges)):
        lines.append("+ Kante: %s -> %s (%d)" % (a, b, new_edges[(a, b)]))
        changes += 1
    for a, b in sorted(set(old_edges) - set(new_edges)):
        lines.append("- Kante: %s -> %s" % (a, b))
        changes += 1
    if not changes:
        lines.append("(keine strukturellen Änderungen)")
    lines.append("= %d Knoten, %d Kanten (vorher %d / %d)"
                 % (len(new_nodes), len(new_edges), len(old_nodes), len(old_edges)))
    return "\n".join(lines)


def emit_graph(repo_root, run_dir, key, dot_source, nodes, edge_weights, notes=()):
    """Schreibt <key>.dot/.svg/.edges.json/.diff.txt ins run_dir, druckt den Diff.

    edge_weights: dict[(src, dst)] -> gewicht. Rückgabe: Exit-Code.
    """
    nodes = sorted(set(nodes))
    edges = sorted([a, b, w] for (a, b), w in edge_weights.items())
    with open(os.path.join(run_dir, key + ".dot"), "w", encoding="utf-8") as f:
        f.write(dot_source)
    ok, err = render_svg(dot_source, os.path.join(run_dir, key + ".svg"))
    if not ok:
        print("FEHLER: dot-Render %s fehlgeschlagen: %s" % (key, err))
        return 1
    data = {
        "generated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "sha": head_sha(repo_root),
        "nodes": nodes,
        "edges": edges,
    }
    with open(os.path.join(run_dir, key + ".edges.json"), "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    old = load_edges(os.path.join(repo_root, "explainer", "graphs", key + ".edges.json"))
    text = diff_text(key, old, nodes, edges)
    for note in notes:
        text += "\n! " + note
    with open(os.path.join(run_dir, key + ".diff.txt"), "w", encoding="utf-8") as f:
        f.write(text + "\n")
    print(text)
    return 0


def atomic_write(path, content):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(content)
    os.replace(tmp, path)
