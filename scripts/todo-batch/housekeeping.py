#!/usr/bin/env python3
"""TODO-Batch, Einordnen 1 (TODO cc0eb3ba): gemergte Batch-TODOs schließen, verwaiste freigeben.

Liest `<state>/details.txt` und das Quell-Repo nur lesend. Gemergt heißt: ein Commit mit dem Trailer
`Todo: <uuid>` ist in `refs/heads/<ziel>`. Letzte Zeile der Ausgabe: die `updates`-Liste;
`<state>/housekeeping.json` für den Bericht.
"""
from __future__ import annotations

import argparse
import sys

import batchlib as b


def trailer_ids(project_path: str, *rev_args: str) -> set[str]:
    log = b.git("-C", project_path, "log", *rev_args, "--grep=^Todo: ", "--format=%B%x00")
    return {found for message in log.split("\x00") for found in b.trailers(message)}


def branch_ids(project_path: str, target: str) -> set[str]:
    refs = b.git("-C", project_path, "for-each-ref", "--format=%(refname)", "refs/heads/mysuricates/").split()
    found: set[str] = set()
    for ref in refs:
        found |= trailer_ids(project_path, f"refs/heads/{target}..{ref}")
    return found


def plan(todos: list[dict], merged: set[str], in_branches: set[str], now=None, stale_hours: int = b.STALE_HOURS):
    moment = b.now_utc(now)
    updates, summary = [], {"closed": [], "released": [], "orphans": [], "stale": []}
    for todo in todos:
        todo_id, tags, status = todo.get("id", ""), set(todo.get("tags") or []), todo.get("status")
        if not b.is_uuid(todo_id):
            continue
        started = b.SELECTED in tags and b.STARTED in tags
        if b.HANDED_OVER in tags and todo_id in merged:
            updates.append(b.update(todo_id, status="DONE", remove=[b.HANDED_OVER, b.SELECTED, b.STARTED],
                                    note="Batch-Commit ist gemergt – erledigt", require_tags=[b.HANDED_OVER]))
            summary["closed"].append(todo_id)
        elif b.HANDED_OVER in tags and todo_id not in in_branches:
            updates.append(b.update(todo_id, remove=[b.HANDED_OVER],
                                    note="Batch-Branch weder gemergt noch vorhanden – wieder frei",
                                    require_tags=[b.HANDED_OVER]))
            summary["released"].append(todo_id)
        elif started and status == "OPEN":
            updates.append(b.update(todo_id, remove=[b.SELECTED, b.STARTED], note="Batch-Lauf ohne Abschluss – wieder frei",
                                    require_tags=[b.SELECTED, b.STARTED], require_status="OPEN"))
            summary["orphans"].append(todo_id)
        elif started and status == "IN_PROGRESS" and b.is_stale(todo, stale_hours, moment):
            updates.append(b.update(todo_id, status="OPEN", remove=[b.SELECTED, b.STARTED],
                                    note=f"Batch-Lauf hängt seit über {stale_hours} h – wieder frei",
                                    require_tags=[b.SELECTED, b.STARTED], require_status="IN_PROGRESS"))
            summary["stale"].append(todo_id)
    return updates, summary


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True)
    parser.add_argument("--project-path", required=True)
    parser.add_argument("--target-branch", default="main")
    parser.add_argument("--stale-hours", type=int, default=b.STALE_HOURS)
    args = parser.parse_args(argv)
    state = b.state(args.state)
    try:
        todos = b.parse_details(b.read_text(state / "details.txt"))
        merged = trailer_ids(args.project_path, f"refs/heads/{args.target_branch}")
        in_branches = branch_ids(args.project_path, args.target_branch)
    except (b.BatchError, ValueError) as error:
        print(f"Aufräumen nicht möglich: {error}")
        return 2
    updates, summary = plan(todos, merged, in_branches, stale_hours=args.stale_hours)
    b.write_json(state / "housekeeping.json", summary)
    print(f"Aufräumen: {len(summary['closed'])} erledigt, {len(summary['released'])} ohne Branch freigegeben, "
          f"{len(summary['orphans'])} verwaist und {len(summary['stale'])} hängend freigegeben")
    b.emit_updates(updates)
    return 0


if __name__ == "__main__":
    sys.exit(main())
