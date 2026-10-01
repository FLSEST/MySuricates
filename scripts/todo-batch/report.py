#!/usr/bin/env python3
"""TODO-Batch, Abarbeiten (TODO cc0eb3ba): Bericht, Merge-Vorschau, Ampel und Dashboard-Kachel – ohne KI.

Prüft zuerst, dass seit der Prüfung niemand den Branch verändert hat (Review und Gesamt-Review dürfen nur lesen);
sonst zurück auf `head.sha`. Ein angenommenes TODO ohne lesbares Review oder ohne echtes Urteil (`ok`/`findings`, nicht etwa
`not_reviewed`) gilt als „mit Hinweis“, nicht als sauber. Schreibt `approval.md` (die Frage der Freigabe), `merge.json` und `widget.json`.
"""
from __future__ import annotations

import argparse
import json
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


def start_skips(not_started: dict, titles: dict) -> str:
    return "; ".join(f"{b.short(t)} {b.plain(titles.get(t, ''), 60)} – {b.plain(why, 120)}"
                     for t, why in list(not_started.items())[:5]) + (" …" if len(not_started) > 5 else "")


def warning(review: dict) -> str:
    """Warum ein angenommenes TODO nur „mit Hinweis“ gilt: kein echtes Review-Urteil und/oder wichtige Befunde."""
    parts = []
    if not review:
        parts.append("kein Review (fehlt oder nicht lesbar)")
    elif not b.reviewed(review):
        parts.append(f"kein Review (Urteil: {review.get('verdict') or '–'})"
                     + (f": {review['reason']}" if review.get("reason") else ""))
    if review.get("major"):
        parts.append("Review: " + "; ".join(review["major"]))
    return "angenommen; " + "; ".join(parts)


def build(state: Path, target: str, run_id: str, date: str) -> tuple[str, str, dict, str]:
    notes = guard_head(state)
    data = b.read_json(state / "results.json", None)
    if data is None and b.read_text(state / "ids.txt").strip():
        summary = "Lauf unvollständig – die Prüfung lieferte keine Ergebnisse; alle TODOs gehen mit batch:failed zurück."
        widget = {"status": "RED", "title": f"TODO-Batch {date}", "summary": summary,
                  "kpis": [{"label": "Angenommen", "value": "0"}], "items": []}
        return "RED", summary, widget, ""
    not_started = b.read_json(state / "not_started.json", {})
    data = data or {"order": [], "todos": {}}
    reviews = b.read_json(state / "reviews.json", {})
    titles = b.read_json(state / "titles.json", {})
    order = data["order"]
    if not order and not_started:
        summary = (f"Kein TODO gestartet – {len(not_started)} TODO(s) nicht gestartet (Start-Update übersprungen): "
                   + start_skips(not_started, titles))
        widget = {"status": "YELLOW", "title": f"TODO-Batch {date}", "summary": b.plain(summary, 500),
                  "kpis": [{"label": "Angenommen", "value": "0"}], "items": []}
        return "YELLOW", summary, widget, ""
    if not order:
        widget = {"status": "GREEN", "title": f"TODO-Batch {date}", "summary": "Kein TODO ausgewählt.",
                  "kpis": [{"label": "Angenommen", "value": "0"}], "items": []}
        return "GREEN", "Kein TODO ausgewählt – nichts zu tun.", widget, ""
    kinds = {}
    for todo_id in order:
        status = data["todos"][todo_id]["status"] or "skipped"
        review = reviews.get(todo_id)
        kinds[todo_id] = ("warned" if status == "accepted" and (not b.reviewed(review) or review.get("major"))
                          else status)
    if data.get("discarded"):
        shown = ", ".join(b.plain(f, 80) for f in data["discarded"][:5])
        notes.append(f"Nicht committete Reste verworfen, geprüft wurde nur Committetes: {shown}"
                     + (" …" if len(data["discarded"]) > 5 else ""))
    if not_started:
        notes.append(f"{len(not_started)} TODO(s) nicht gestartet (Start-Update übersprungen): "
                     + start_skips(not_started, titles))
    if data.get("flaky"):
        notes.append("Gate erst im zweiten Versuch grün (instabil?): " + ", ".join(b.plain(n, 40) for n in data["flaky"]))
    count = {kind: sum(1 for value in kinds.values() if value == kind) for kind in ICONS}
    accepted = count["accepted"] + count["warned"]
    merge = b.merge_preview(target) if accepted else {"state": "–", "files": []}
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
                  "warned": warning(review),
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
