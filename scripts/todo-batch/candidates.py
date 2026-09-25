#!/usr/bin/env python3
"""TODO-Batch, Einordnen 3 (TODO cc0eb3ba): Kandidaten für die Triage vorfiltern – ohne KI.

Kandidat: Status OPEN, kein `batch:*`-Tag, keine offene Frage. Läuft noch ein Batch (IN_PROGRESS mit
`batch:selected`), gibt es keine. Nur mit Kandidaten entsteht `<state>/candidates.json` – daran hängt
`run_if` der Triage. Ausgabe: die Kandidaten als eine JSON-Zeile für den Prompt.
"""
from __future__ import annotations

import argparse
import json
import sys

import batchlib as b

FIELDS = ("id", "priority", "tags", "title", "complexity", "suggestedEffort", "description")


def select(todos: list[dict], limit: int) -> tuple[bool, list[dict]]:
    if any(t.get("status") == "IN_PROGRESS" and b.SELECTED in (t.get("tags") or []) for t in todos):
        return True, []
    chosen = [t for t in todos
              if t.get("status") == "OPEN"
              and b.is_uuid(t.get("id"))
              and not t.get("openQuestion")
              and not any(str(tag).startswith(b.BATCH_PREFIX) for tag in (t.get("tags") or []))]
    return False, [{key: t.get(key) for key in FIELDS} for t in chosen[:limit]]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True)
    parser.add_argument("--max", type=int, default=40)
    args = parser.parse_args(argv)
    state = b.state(args.state)
    try:
        todos = b.parse_details(b.read_text(state / "details.txt"))
    except (b.BatchError, ValueError) as error:
        print(f"Kandidaten nicht lesbar: {error}")
        return 2
    active, chosen = select(todos, args.max)
    target = state / "candidates.json"
    if chosen:
        b.write_json(target, chosen)
    elif target.exists():
        target.unlink()
    (state / "active.flag").write_text("1" if active else "0", encoding="utf-8")
    print(json.dumps({"active": active, "count": len(chosen), "candidates": chosen},
                     ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
