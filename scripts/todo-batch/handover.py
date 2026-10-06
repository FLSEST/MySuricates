#!/usr/bin/env python3
"""TODO batch, processing (TODO cc0eb3ba): handover after approval – branch ref into the source repo, update the TODOs.

"Übernehmen" → `refs/heads/mysuricates/batch-<run8>` in the source repo: only a new ref; working tree, other refs
and remotes stay untouched, an existing ref is never overwritten, nothing is pushed. If the ref already points
to exactly this state (resumption of the same run), the handover counts as done. The merge preview in the
note comes from the time of the handover, not from the report. "Verwerfen" → nothing handed over, traffic light yellow.
No answer (deadline expired) → `--on-timeout`. If the check results are missing (run aborted earlier),
all TODOs of the batch go back with `batch:failed`. Removes build directories in its own clone.
Last line of the output: the `updates` list.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

import batchlib as b


INCOMPLETE = "Lauf unvollständig – keine Prüfergebnisse"


def load_results(state: Path) -> dict:
    """`results.json`, or – if the run aborted before the check – every TODO from `ids.txt` as failed."""
    data = b.read_json(state / "results.json", None)
    if data is not None:
        return data
    ids = [line.strip() for line in b.read_text(state / "ids.txt").splitlines() if b.is_uuid(line.strip())]
    return {"order": ids, "todos": {i: {"status": "failed", "reason": INCOMPLETE} for i in ids}}


def decide(answer: str, has_accepted: bool, on_timeout: str) -> tuple[str, str]:
    if not has_accepted:
        return "none", "nichts angenommen"
    normalized = (answer or "").strip().lower()
    if normalized == "übernehmen":
        return "provide", "freigegeben"
    if normalized == "verwerfen":
        return "discard", "verworfen"
    return ("provide" if on_timeout == "provide" else "discard"), "keine Antwort in der Frist"


def export(project_path: str, run_id: str) -> str:
    ref = f"refs/heads/mysuricates/batch-{b.short(run_id)}"
    if b.git_ok("-C", project_path, "show-ref", "--verify", "--quiet", ref):
        if b.git("-C", project_path, "rev-parse", ref).strip() == b.git("rev-parse", "HEAD").strip():
            return ref.removeprefix("refs/heads/")   # same run, already handed over
        raise b.BatchError(f"{ref} gibt es schon – nichts überschrieben")
    root = b.git("rev-parse", "--show-toplevel").strip()
    b.git("-C", project_path, "fetch", "--no-tags", "--quiet", root, f"HEAD:{ref}")
    return ref.removeprefix("refs/heads/")


def plan(decision: str, data: dict, branch: str, merge_state: str, why: str, failure: str | None) -> list[dict]:
    updates = []
    for todo_id in data["order"]:
        result = data["todos"][todo_id]
        accepted = result["status"] == "accepted"
        if accepted and decision == "provide" and not failure:
            updates.append(b.update(todo_id, add=[b.HANDED_OVER], remove=[b.SELECTED, b.STARTED], require_tags=[b.SELECTED],
                                    note=f"Übergeben in Branch {branch} (Merge-Vorschau: {merge_state}; {why})"))
        elif accepted and decision == "discard":
            updates.append(b.update(todo_id, status="OPEN", add=[b.REJECTED], remove=[b.SELECTED, b.STARTED],
                                    require_tags=[b.SELECTED], note=f"Batch verworfen ({why})"))
        else:
            reason = failure if (accepted and failure) else (result.get("reason") or "nicht angenommen")
            updates.append(b.update(todo_id, status="OPEN", add=[b.FAILED], remove=[b.SELECTED, b.STARTED],
                                    require_tags=[b.SELECTED], note=f"Batch: {b.plain(reason, 300)}"))
    return updates


def cleanup(paths: list[str]) -> list[str]:
    root = Path(b.git("rev-parse", "--show-toplevel").strip()).resolve()
    removed = []
    for raw in paths:
        target = (root / raw).resolve()
        if target != root and root in target.parents and target.is_dir():
            shutil.rmtree(target, ignore_errors=True)
            removed.append(raw)
    return removed


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True)
    parser.add_argument("--answer", default="")
    parser.add_argument("--on-timeout", choices=["provide", "discard"], default="provide")
    parser.add_argument("--project-path", required=True)
    parser.add_argument("--target-branch", default="main")
    parser.add_argument("--run", required=True)
    parser.add_argument("--cleanup", nargs="*", default=[])
    args = parser.parse_args(argv)
    state = b.state(args.state)
    try:
        b.ensure_isolated(args.project_path)
        b.require_isolated(state)
    except b.BatchError as error:
        print(f"Übergabe nicht möglich: {error}")
        return 2
    data = load_results(state)
    decision, why = decide(args.answer, any(r["status"] == "accepted" for r in data["todos"].values()), args.on_timeout)
    branch, failure, merge_state = "–", None, "–"
    if decision == "provide":
        merge_state = b.merge_preview(args.target_branch)["state"]
        try:
            branch = export(args.project_path, args.run)
        except b.BatchError as error:
            failure = f"Übergabe gescheitert: {error}"
    if failure:
        print("gate_status: RED")
        print("gate_category: todo-batch-handover")
        print(f"gate_description: {b.plain(failure, 300)}")
    elif decision == "discard":
        print("gate_status: YELLOW")
        print("gate_category: todo-batch-handover")
        print(f"gate_description: Batch verworfen ({why}) – nichts übergeben")
    widget = b.read_json(state / "widget.json", None)
    if widget:
        outcome = {"provide": f"Übergeben: {branch}", "discard": "Verworfen", "none": "Nichts zu übergeben"}[decision]
        if failure:
            outcome, widget["status"] = failure, "RED"
        elif decision == "discard":
            widget["status"] = "YELLOW"
        widget["summary"] = b.plain(f"{widget.get('summary', '')} {outcome} ({why}).", 500)
        print("dashboard_widget: " + json.dumps(widget, ensure_ascii=False))
    removed = cleanup(args.cleanup)
    print(f"Übergabe: {decision} ({why})" + (f" → {branch}" if decision == "provide" and not failure else "")
          + (f" – {failure}" if failure else "") + (f"; aufgeräumt: {', '.join(removed)}" if removed else ""))
    b.emit_updates(plan(decision, data, branch, merge_state, why, failure))
    return 0


if __name__ == "__main__":
    sys.exit(main())
