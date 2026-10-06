#!/usr/bin/env python3
"""TODO batch, triage 5 (TODO cc0eb3ba): check the triage's answer and translate it into tags – without AI.

Reads the last `TRIAGE:` line from `<state>/triage.txt`; only IDs from `candidates.json`, at most `--max`,
one area. Last line of the output: the `updates` list; `<state>/selection.json` for the report.
"""
from __future__ import annotations

import argparse
import json
import sys

import batchlib as b

AREAS = {"backend", "frontend", "workflow", "docs"}


def _result(area, selected=(), dropped=(), error=None) -> dict:
    return {"area": area, "selected": list(selected), "dropped": list(dropped), "error": error}


def decide(triage_text: str, candidates: list[dict], limit: int, preselected: int = 0) -> dict:
    limit = max(0, limit - preselected)
    raw = b.last_marker(triage_text, "TRIAGE:")
    if raw is None:
        return _result(None, error="keine TRIAGE-Zeile")
    try:
        answer = json.loads(raw)
    except json.JSONDecodeError as error:
        return _result(None, error=f"TRIAGE-Zeile ist kein JSON ({error.msg})")
    if not isinstance(answer, dict):
        return _result(None, error="TRIAGE-Zeile ist kein Objekt")
    area = str(answer.get("area", "")).strip().lower()
    wanted = answer.get("selected") or []
    if area == "none" or not wanted:
        return _result("none")
    if area not in AREAS:
        return _result(b.plain(area, 40), error=f"unbekannter Bereich '{b.plain(area, 40)}'")
    by_id = {c.get("id"): c for c in candidates}
    chosen, seen, dropped = [], set(), []
    for item in wanted if isinstance(wanted, list) else []:
        todo_id = str(item.get("id", "")).strip().lower() if isinstance(item, dict) else ""
        if todo_id not in by_id or todo_id in seen:
            dropped.append(b.short(todo_id) or "?")
            continue
        seen.add(todo_id)
        chosen.append({"id": todo_id, "title": by_id[todo_id].get("title", ""),
                       "reason": b.plain(item.get("reason", ""), 300), "verify": b.plain(item.get("verify", ""), 300)})
    dropped += [b.short(c["id"]) for c in chosen[limit:]]
    return _result(area, chosen[:limit], dropped)


def updates_for(decision: dict, run_id: str) -> list[dict]:
    return [b.update(item["id"], add=[b.SELECTED], require_status="OPEN",
                     note=f"Batch-Auswahl ({decision['area']}, Lauf {b.short(run_id)}): {item['reason']} "
                          f"· Prüfweg: {item['verify']}")
            for item in decision["selected"]]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True)
    parser.add_argument("--max", type=int, default=6)
    parser.add_argument("--run", required=True)
    args = parser.parse_args(argv)
    state = b.state(args.state)
    preselected = int(b.read_text(state / "preselected.txt").strip() or 0)
    decision = decide(b.read_text(state / "triage.txt"), b.read_json(state / "candidates.json", []), args.max, preselected)
    b.write_json(state / "selection.json", decision)
    print(f"Auswahl: {len(decision['selected'])} TODO(s), Bereich {decision['area']}"
          + (f" – {decision['error']}" if decision.get("error") else ""))
    b.emit_updates(updates_for(decision, args.run))
    return 0


if __name__ == "__main__":
    sys.exit(main())
