#!/usr/bin/env python3
"""TODO-Batch, Einordnen 3 (TODO cc0eb3ba): Kandidaten für die Triage vorfiltern – ohne KI.

Kandidat: Status OPEN, kein `batch:*`-Tag, keine offene Frage. Läuft noch ein Batch (IN_PROGRESS mit
`batch:selected` und `batch:started`, nicht hängend), gibt es keine; von Hand ausgewählte zählen gegen `--batch-max`. Nur mit Kandidaten entsteht `<state>/candidates.json` – daran hängt
`run_if` der Triage. Ausgabe: die Kandidaten als eine JSON-Zeile für den Prompt.

Dazu `near_limit` (TODO 8bbc2368): Dateien, die höchstens `--size-margin` Zeilen unter ihrer Obergrenze stehen
(oder darüber). `--size-limits <grenzdatei> <quellwurzel>`: Die Grenzen stehen in der Grenzdatei im Format von
`SourceSizeRatchetTest` (`MAX_LINES = N` als Standard, `entry("<pfad>", N)` je Ausnahme, Pfade relativ zur
Quellwurzel); gezählt werden die `.java`-Dateien unter der Quellwurzel. Ohne Grenzdatei bleibt die Liste leer – ein
Repo ohne Größen-Ratchet lässt die beiden Werte im Kontext leer oder weg. Fehlt ein Kontext-Wert ganz, lässt die
Plattform den Platzhalter wörtlich stehen (`{{ name }}` → drei Wörter); auch das gilt als „nicht gesetzt“.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

import batchlib as b

_MAX_LINES = re.compile(r"\bMAX_LINES\s*=\s*(\d+)")
_ENTRY = re.compile(r'entry\(\s*"([^"]+)"\s*,\s*(\d+)\s*\)')
DEFAULT_MAX_LINES = 300
NEAR_LIMIT_MAX = 40
FIELDS = ("id", "priority", "tags", "title", "complexity", "suggestedEffort", "description")


def select(todos: list[dict], limit: int, batch_max: int | None = None, now=None,
           stale_hours: int = b.STALE_HOURS) -> tuple[bool, list[dict], int]:
    """(Batch läuft?, Kandidaten, schon von Hand/früher ausgewählte, noch nicht gestartete TODOs)."""
    moment = b.now_utc(now)
    if any(b.active_batch(t, stale_hours, moment) for t in todos):
        return True, [], 0
    preselected = sum(1 for t in todos if t.get("status") == "OPEN" and b.SELECTED in (t.get("tags") or [])
                      and b.STARTED not in (t.get("tags") or []))
    if batch_max is not None and preselected >= batch_max:
        return False, [], preselected
    chosen = [t for t in todos
              if t.get("status") == "OPEN"
              and b.is_uuid(t.get("id"))
              and not t.get("openQuestion")
              and not any(str(tag).startswith(b.BATCH_PREFIX) for tag in (t.get("tags") or []))]
    return False, [{key: t.get(key) for key in FIELDS} for t in chosen[:limit]], preselected


def ceilings(ratchet_text: str) -> tuple[int, dict[str, int]]:
    """(Standard-Obergrenze, Obergrenze je Datei relativ zur Quellwurzel) aus der Grenzdatei."""
    found = _MAX_LINES.search(ratchet_text)
    return (int(found.group(1)) if found else DEFAULT_MAX_LINES,
            {path: int(limit) for path, limit in _ENTRY.findall(ratchet_text)})


def near_limit(ratchet: Path, root: Path, margin: int, base: Path | None = None) -> list[dict]:
    """Dateien, die die Triage meiden soll: Abstand zur Obergrenze unter `margin` Zeilen, knappste zuerst."""
    if not ratchet.is_file() or not root.is_dir():
        return []
    default, listed = ceilings(ratchet.read_text(encoding="utf-8", errors="replace"))
    near = []
    for path in sorted(root.rglob("*.java")):
        rel = path.relative_to(root).as_posix()
        lines = len(path.read_text(encoding="utf-8", errors="replace").splitlines())
        ceiling = listed.get(rel, default)
        if ceiling - lines < margin:
            shown = path.relative_to(base) if base and path.is_relative_to(base) else path
            near.append({"file": shown.as_posix(), "lines": lines, "ceiling": ceiling})
    near.sort(key=lambda n: (n["ceiling"] - n["lines"], n["file"]))
    return near[:NEAR_LIMIT_MAX]


def size_limits(tokens: list[str]) -> tuple[str, str]:
    """(Grenzdatei, Quellwurzel) aus `--size-limits`; ein unaufgelöster Platzhalter `{{ … }}` zählt als leer."""
    values, inside = [], False
    for token in tokens or []:
        if token == "{{" or (token.startswith("{{") and not token.endswith("}}")):
            inside = True
        elif inside:
            if token.endswith("}}"):
                inside = False
                values.append("")
        else:
            values.append("" if token.startswith("{{") else token)
    values += ["", ""]
    return values[0].strip(), values[1].strip()


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True)
    parser.add_argument("--max", type=int, default=40)
    parser.add_argument("--batch-max", type=int, default=None)
    parser.add_argument("--size-limits", nargs="*", default=[], metavar="PFAD",
                        help="Grenzdatei (Format SourceSizeRatchetTest) und Quellwurzel; leer oder fehlend = keine Liste")
    parser.add_argument("--size-margin", type=int, default=15)
    args = parser.parse_args(argv)
    state = b.state(args.state)
    try:
        todos = b.parse_details(b.read_text(state / "details.txt"))
    except (b.BatchError, ValueError) as error:
        print(f"Kandidaten nicht lesbar: {error}")
        return 2
    active, chosen, preselected = select(todos, args.max, args.batch_max)
    target = state / "candidates.json"
    if chosen:
        b.write_json(target, chosen)
    elif target.exists():
        target.unlink()
    (state / "active.flag").write_text("1" if active else "0", encoding="utf-8")
    (state / "preselected.txt").write_text(str(preselected), encoding="utf-8")
    ratchet, root = size_limits(args.size_limits)
    near = (near_limit(Path(ratchet), Path(root), args.size_margin, base=Path.cwd())
            if chosen and ratchet and root else [])
    print(json.dumps({"active": active, "count": len(chosen), "preselected": preselected, "candidates": chosen,
                      "near_limit": near}, ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
