#!/usr/bin/env python3
"""TODO-Batch, Abarbeiten (TODO cc0eb3ba): Bericht, Merge-Vorschau, Ampel und Dashboard-Kachel – ohne KI.

Prüft zuerst, dass seit der Prüfung niemand den Branch verändert hat (Review und Gesamt-Review dürfen nur lesen);
sonst zurück auf `head.sha`. Ein angenommenes TODO ohne lesbares Review gilt als „mit Hinweis“, nicht als sauber. Schreibt `approval.md` (die Frage der Freigabe), `merge.json` und `widget.json`.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

import batchlib as b

ICONS = {"accepted": "✅", "warned": "⚠️", "dropped": "❌", "skipped": "⏭"}


def guard_head(state: Path) -> list[str]:
    expected = b.read_text(state / "head.sha").strip()
    if not expected:
        return []
    if b.git("rev-parse", "HEAD").strip() == expected and not b.git("status", "--porcelain").strip():
        return []
    b.git("reset", "-q", "--hard", expected)
    b.git_ok("clean", "-fdq")
    return ["Nach der Prüfung war der Arbeitsstand verändert – zurückgesetzt auf den geprüften Stand."]


def merge_preview(target: str) -> dict:
    if not b.git_ok("fetch", "-q", "origin", f"+refs/heads/{target}:refs/remotes/origin/{target}"):
        return {"state": "unbekannt", "files": []}
    result = subprocess.run(["git", "merge-tree", "--write-tree", "--name-only", f"origin/{target}", "HEAD"],
                            capture_output=True, text=True)
    if result.returncode == 0:
        return {"state": "sauber", "files": []}
    files = []
    for line in result.stdout.splitlines()[1:]:
        if not line.strip():
            break
        files.append(line.strip())
    return {"state": "Konflikt", "files": files[:10]}


def build(state: Path, target: str, run_id: str, date: str) -> tuple[str, str, dict, str]:
    notes = guard_head(state)
    data = b.read_json(state / "results.json", None)
    if data is None and b.read_text(state / "ids.txt").strip():
        summary = "Lauf unvollständig – die Prüfung lieferte keine Ergebnisse; alle TODOs gehen mit batch:failed zurück."
        widget = {"status": "RED", "title": f"TODO-Batch {date}", "summary": summary,
                  "kpis": [{"label": "Angenommen", "value": "0"}], "items": []}
        return "RED", summary, widget, ""
    data = data or {"order": [], "todos": {}}
    reviews = b.read_json(state / "reviews.json", {})
    titles = b.read_json(state / "titles.json", {})
    order = data["order"]
    if not order:
        widget = {"status": "GREEN", "title": f"TODO-Batch {date}", "summary": "Kein TODO ausgewählt.",
                  "kpis": [{"label": "Angenommen", "value": "0"}], "items": []}
        return "GREEN", "Kein TODO ausgewählt – nichts zu tun.", widget, ""
    kinds = {}
    for todo_id in order:
        status = data["todos"][todo_id]["status"] or "skipped"
        review = reviews.get(todo_id)
        kinds[todo_id] = "warned" if status == "accepted" and (not review or review.get("major")) else status
    if data.get("discarded"):
        shown = ", ".join(b.plain(f, 80) for f in data["discarded"][:5])
        notes.append(f"Nicht committete Reste verworfen, geprüft wurde nur Committetes: {shown}"
                     + (" …" if len(data["discarded"]) > 5 else ""))
    count = {kind: sum(1 for value in kinds.values() if value == kind) for kind in ICONS}
    accepted = count["accepted"] + count["warned"]
    merge = merge_preview(target) if accepted else {"state": "–", "files": []}
    b.write_json(state / "merge.json", merge)
    if accepted == 0:
        status = "RED"
    elif (count["dropped"] or count["skipped"] or count["warned"] or data.get("foreign") or notes
          or merge["state"] == "Konflikt"):
        status = "YELLOW"
    else:
        status = "GREEN"
    summary = (f"{accepted} von {len(order)} TODO(s) angenommen, {count['dropped']} herausgefallen, "
               f"{count['skipped']} übersprungen. Merge-Vorschau: {merge['state']}.")
    lines = [f"## TODO-Batch {date} · Lauf {b.short(run_id)}", "",
             f"Nach „Übernehmen“ liegt der Branch `mysuricates/batch-{b.short(run_id)}` im Repo; gemergt wird von Hand. "
             f"Merge-Vorschau gegen `{target}`: **{merge['state']}**"
             + (f" ({', '.join(merge['files'])})" if merge["files"] else "") + ".", "",
             "| | TODO | Ergebnis |", "|---|---|---|"]
    for todo_id in order:
        result, review = data["todos"][todo_id], reviews.get(todo_id, {})
        detail = {"accepted": f"{len(result['commits'])} Commit(s), {len(result['files'])} Datei(en), {result['lines']} Zeilen",
                  "warned": ("angenommen; Review: " + "; ".join(review["major"]) if review.get("major")
                             else "angenommen; kein Review (fehlt oder nicht lesbar)"),
                  "dropped": result.get("reason") or "herausgefallen",
                  "skipped": result.get("reason") or "übersprungen"}[kinds[todo_id]]
        lines.append(f"| {ICONS[kinds[todo_id]]} | {b.short(todo_id)} {b.plain(titles.get(todo_id, ''), 100)} "
                     f"| {b.plain(detail, 300)} |")
    overall = [l.strip() for l in b.read_text(state / "gesamt.txt").splitlines()
               if l.strip() and not l.strip().startswith(("GESAMT:", "Übersprungen (run_if"))]
    if overall and accepted:
        lines += ["", "**Gesamt-Review:**"] + [f"> {b.plain(l, 300)}" for l in overall[:8]]
    lines += [""] + [f"_{note}_" for note in notes]
    lines += ["", "**Übernehmen**: Branch bereitstellen, die TODOs bleiben bis zum Merge in Arbeit. "
                  "**Verwerfen**: nichts übernehmen, alle TODOs gehen mit `batch:rejected` zurück."]
    widget = {"status": status, "title": f"TODO-Batch {date}", "summary": summary[:500],
              "kpis": [{"label": "Angenommen", "value": str(accepted)},
                       {"label": "Herausgefallen", "value": str(count["dropped"])},
                       {"label": "Übersprungen", "value": str(count["skipped"])},
                       {"label": "Mit Hinweisen", "value": str(count["warned"])}],
              "items": [f"{ICONS[kinds[t]]} {b.plain(titles.get(t, ''), 180)}" for t in order][:10]}
    return status, summary, widget, "\n".join(lines) + "\n"


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True)
    parser.add_argument("--target-branch", default="main")
    parser.add_argument("--run", required=True)
    parser.add_argument("--date", required=True)
    args = parser.parse_args(argv)
    state = b.state(args.state)
    status, summary, widget, approval = build(state, args.target_branch, args.run, args.date)
    (state / "approval.md").write_text(approval, encoding="utf-8")
    b.write_json(state / "widget.json", widget)
    print(f"gate_status: {status}")
    print("gate_category: todo-batch")
    print(f"gate_description: {b.plain(summary, 300)}")
    print("dashboard_widget: " + json.dumps(widget, ensure_ascii=False))
    print(summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
