#!/usr/bin/env python3
"""JPA-Entity-Beziehungsdiagramm aus @Entity-Klassen (Owning-Seite zeichnet die Kante).

Aufruf: extract_entities.py <repo_root> <run_dir>
Output: entities.{dot,svg,edges.json,diff.txt} im run_dir + Diff auf stdout.
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common

JAVA_ROOT = os.path.join("ai-automation-backend", "src", "main", "java")
ENTITY_RE = re.compile(r"^\s*@Entity(?![\w])", re.M)
CLASS_RE = re.compile(r"^\s*(?:public\s+|abstract\s+|final\s+)*class\s+(\w+)", re.M)
TABLE_RE = re.compile(r'@Table\s*\(\s*name\s*=\s*"(\w+)"')
REL_RE = re.compile(r"@(ManyToOne|OneToMany|OneToOne|ManyToMany)\b")
FIELD_RE = re.compile(
    r"^(?:private|protected|public)\s+(?:final\s+)?([\w.]+(?:\s*<[^;={}]*>)?)\s+(\w+)\s*(?:=[^;]*)?;")
GENERIC_RE = re.compile(r"<\s*([\w.]+)\s*>")


def parse_entity(text):
    """(klasse, tabelle, [(relation, zieltyp, feld, mapped_by)]) oder None."""
    cm = CLASS_RE.search(text)
    if not cm:
        return None
    name = cm.group(1)
    tm = TABLE_RE.search(text)
    table = tm.group(1) if tm else ""
    relations = []
    pending = []
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        s = lines[i].strip()
        if s.startswith("@"):
            ann = s
            while ann.count("(") > ann.count(")") and i + 1 < len(lines):
                i += 1
                ann += " " + lines[i].strip()
            pending.append(ann)
        else:
            fm = FIELD_RE.match(s)
            if fm and pending:
                anns = " ".join(pending)
                rm = REL_RE.search(anns)
                if rm:
                    ftype = fm.group(1)
                    gm = GENERIC_RE.search(ftype)
                    target = (gm.group(1) if gm else ftype).split(".")[-1]
                    relations.append((rm.group(1), target, fm.group(2), "mappedBy" in anns))
                pending = []
            elif s and not s.startswith(("//", "*", "/*")):
                pending = []
        i += 1
    return name, table, relations


def main():
    repo_root, run_dir = sys.argv[1], sys.argv[2]
    java_root = os.path.join(repo_root, JAVA_ROOT)
    entities = {}   # klasse -> (tabelle, relations)
    for dirpath, _, filenames in os.walk(java_root):
        for fn in filenames:
            if not fn.endswith(".java"):
                continue
            with open(os.path.join(dirpath, fn), encoding="utf-8", errors="replace") as f:
                text = f.read()
            if not ENTITY_RE.search(text):
                continue
            parsed = parse_entity(text)
            if parsed:
                entities[parsed[0]] = (parsed[1], parsed[2])

    owning = []   # (src, dst, kind, feld)
    inverse = []
    for src, (_, rels) in entities.items():
        for kind, target, field, mapped_by in rels:
            if target not in entities or target == src:
                continue
            (inverse if mapped_by else owning).append((src, target, kind, field))

    owning_pairs = {(a, b) for a, b, _, _ in owning}
    edge_weights = {}
    lines = [
        "digraph entities {",
        "  rankdir=LR;",
        '  graph [fontname="Helvetica"];',
        '  node [shape=record, style=filled, fillcolor="#fef3c7", color="#d6d3d1", fontname="Helvetica", fontsize=11];',
        '  edge [color="#78716c", fontname="Helvetica", fontsize=9, arrowsize=0.7];',
    ]
    for name in sorted(entities):
        table = entities[name][0]
        label = "{%s|%s}" % (name, table or "?")
        lines.append("  %s [label=%s];" % (common.q(name), common.q(label)))
    for src, dst, kind, field in sorted(owning):
        label = "%s%s %s" % (kind[0].lower(), kind[1:], field)
        lines.append("  %s -> %s [label=%s];" % (common.q(src), common.q(dst), common.q(label)))
        edge_weights[(src, dst)] = edge_weights.get((src, dst), 0) + 1
    for src, dst, kind, field in sorted(inverse):
        # Inverse-Seite nur zeichnen, wenn die Owning-Seite nicht ohnehin sichtbar ist
        if (dst, src) in owning_pairs:
            continue
        label = "%s%s %s (inverse)" % (kind[0].lower(), kind[1:], field)
        lines.append("  %s -> %s [label=%s, style=dashed];" % (common.q(src), common.q(dst), common.q(label)))
        edge_weights[(src, dst)] = edge_weights.get((src, dst), 0) + 1
    lines.append("}")

    sys.exit(common.emit_graph(repo_root, run_dir, "entities",
                               "\n".join(lines) + "\n", set(entities), edge_weights))


if __name__ == "__main__":
    main()
