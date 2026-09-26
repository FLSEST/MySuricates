#!/usr/bin/env python3
"""TODO-Batch, Einordnen 7 (TODO cc0eb3ba): Ampel und Dashboard-Kachel – ohne KI."""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import batchlib as b


def report(state: Path, date: str) -> tuple[str, str, dict]:
    house = b.read_json(state / "housekeeping.json", {"closed": [], "released": [], "orphans": []})
    selection = b.read_json(state / "selection.json", None)
    active = b.read_text(state / "active.flag").strip() == "1"
    selected = (selection or {}).get("selected", [])
    if active:
        status, summary = "GREEN", "Ein Batch läuft noch oder wartet auf Freigabe – heute keine neue Auswahl."
    elif selection and selection.get("error"):
        status, summary = "YELLOW", f"Triage-Antwort unbrauchbar: {selection['error']}"
    elif selected:
        status, summary = "GREEN", f"{len(selected)} TODO(s) für den Batch ausgewählt (Bereich {selection['area']})."
    else:
        status, summary = "GREEN", "Heute erfüllt kein TODO die Kriterien."
    widget = {
        "status": status,
        "title": f"TODO-Batch: Einordnen {date}",
        "summary": b.plain(summary, 500),
        "kpis": [{"label": "Ausgewählt", "value": str(len(selected))},
                 {"label": "Gemergt erledigt", "value": str(len(house["closed"]))},
                 {"label": "Freigegeben", "value": str(len(house["released"]) + len(house["orphans"]))}],
        "items": [b.plain(item.get("title", ""), 200) for item in selected][:10],
    }
    return status, summary, widget


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True)
    parser.add_argument("--date", required=True)
    args = parser.parse_args(argv)
    status, summary, widget = report(b.state(args.state), args.date)
    print(f"gate_status: {status}")
    print("gate_category: todo-batch-triage")
    print(f"gate_description: {b.plain(summary, 300)}")
    print("dashboard_widget: " + json.dumps(widget, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
