#!/usr/bin/env python3
"""TODO-Batch, Einordnen 3 (TODO cc0eb3ba): Kandidaten für die Triage vorfiltern – ohne KI.

Kandidat: Status OPEN, kein `batch:*`-Tag, keine offene Frage. Läuft noch ein Batch (IN_PROGRESS mit
`batch:selected` und `batch:started`, nicht hängend), gibt es keine; von Hand ausgewählte zählen gegen `--batch-max`. Nur mit Kandidaten entsteht `<state>/candidates.json` – daran hängt
`run_if` der Triage. Ausgabe: die Kandidaten als eine JSON-Zeile für den Prompt.
"""
from __future__ import annotations

import argparse
import json
import sys

import batchlib as b

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


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True)
    parser.add_argument("--max", type=int, default=40)
    parser.add_argument("--batch-max", type=int, default=None)
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
    print(json.dumps({"active": active, "count": len(chosen), "preselected": preselected, "candidates": chosen},
                     ensure_ascii=False, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
