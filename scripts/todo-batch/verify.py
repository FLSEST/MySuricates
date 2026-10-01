#!/usr/bin/env python3
"""TODO-Batch, Abarbeiten (TODO cc0eb3ba): Commits den TODOs zuordnen, Wächter, Gates, lineare Nachprüfung.

Geprüft wird nur, was committet ist: Nicht-Committetes im Klon wird vorher verworfen und in `results.json`
unter `discarded` genannt. Läuft nur in einem Klon, den prepare als isoliert bestätigt hat (sonst Exit 2).
Nach dem Umsetzen: Zuordnung über den Trailer `Todo: <uuid>` (Commits ohne gültigen Trailer fallen heraus),
Wächter je TODO (verbotene Pfade, Zeilen-Obergrenze), dann die Gates auf dem ganzen Bereich. Rot → vom
Basis-Commit aus je TODO übernehmen und prüfen; rote TODOs fallen heraus (Vermerk mit der ersten Fehlerzeile des
Gates), die übrigen bauen auf dem geprüften Stand weiter. Leere Commits kommen nicht in den Branch; ein Gate, das erst
im zweiten Versuch grün wird, steht unter `flaky`. `--after-review`: TODOs mit Blocker aus dem Review fallen heraus, der Rest wird neu geprüft.
Ergebnis: `results.json`, `accepted.txt`, `head.sha`; nach dem Review auch `reviews.json`, `report-draft.md`.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import batchlib as b
from gates import LOCKFILES, GateConfig, changed_files, flaky, red_reasons, run_gates, scope_for


def commits_since(base: str) -> list[str]:
    return b.git("rev-list", "--reverse", f"{base}..HEAD").split()


def todo_of(sha: str, known: set[str]) -> str | None:
    found = {t for t in b.trailers(b.git("log", "-1", "--format=%B", sha)) if t in known}
    return found.pop() if len(found) == 1 else None


def files_of(sha: str) -> list[str]:
    return [f for f in b.git("diff-tree", "--no-commit-id", "--name-only", "-r", "--root", sha).splitlines() if f]


def lines_of(sha: str) -> int:
    total = 0
    for row in b.git("show", "--numstat", "--format=", sha).splitlines():
        parts = row.split("\t")
        if len(parts) == 3 and parts[0].isdigit() and parts[1].isdigit() and not parts[2].endswith(LOCKFILES):
            total += int(parts[0]) + int(parts[1])
    return total


def group(base: str, ids: list[str]) -> tuple[dict[str, list[str]], list[str]]:
    known, groups, foreign = set(ids), {todo_id: [] for todo_id in ids}, []
    for sha in commits_since(base):
        todo_id = todo_of(sha, known)
        (groups[todo_id] if todo_id else foreign).append(sha)
    return groups, foreign


def guard(files: list[str], lines: int, forbidden: list[str], max_lines: int) -> str | None:
    hit = sorted({f for f in files for prefix in forbidden if f == prefix.rstrip("/") or f.startswith(prefix)})
    if hit:
        return "verbotene Pfade: " + ", ".join(hit[:5])
    if lines > max_lines:
        return f"{lines} geänderte Zeilen (Grenze {max_lines})"
    return None


def pick(shas: list[str]) -> bool:
    if b.git_ok("cherry-pick", *shas):
        return True
    b.git_ok("cherry-pick", "--abort")
    return False


def reset_to(sha: str) -> None:
    """Harter Rücksprung samt unversionierter Reste, damit kein herausgefallenes TODO im Baum weiterlebt."""
    b.git("reset", "-q", "--hard", sha)
    b.git("clean", "-fdq")


def rebuild(base: str, ordered: list[tuple[str, list[str]]], results: dict) -> list[str]:
    """Baut den Branch vom Basis-Commit aus nur aus diesen TODOs neu auf; Konflikt → TODO fällt heraus."""
    reset_to(base)
    kept = []
    for todo_id, shas in ordered:
        before = b.git("rev-parse", "HEAD").strip()
        if pick(shas):
            kept.append(todo_id)
        else:
            reset_to(before)
            results[todo_id].update(status="dropped", reason="Konflikt beim Übernehmen")
    return kept


def linear(base: str, ordered: list[tuple[str, list[str]]], cfg: GateConfig, results: dict) -> list[str]:
    """Je TODO übernehmen und prüfen; rot → zurück, die übrigen bauen auf dem geprüften Stand weiter.

    Rückgabe: die Gates, die dabei erst im zweiten Versuch grün wurden."""
    reset_to(base)
    retried: list[str] = []
    for todo_id, shas in ordered:
        before = b.git("rev-parse", "HEAD").strip()
        if not pick(shas):
            reset_to(before)
            results[todo_id].update(status="dropped", reason="Konflikt beim Übernehmen")
            continue
        ok, lines = run_gates(scope_for(changed_files(before), cfg), f"{base}..HEAD", cfg, f"linear-{b.short(todo_id)}")
        if ok:
            retried += flaky(lines)
            results[todo_id].update(status="accepted", reason=None)
        else:
            reset_to(before)
            results[todo_id].update(status="dropped", reason="Gate rot: " + "; ".join(red_reasons(lines)))
    return sorted(set(retried))


def initial(state: Path, cfg: GateConfig, forbidden: list[str], max_lines: int) -> dict:
    base = b.read_text(state / "base.sha").strip()
    if not base:
        raise b.BatchError("base.sha fehlt")
    ids = [line.strip() for line in b.read_text(state / "ids.txt").splitlines() if line.strip()]
    discarded = b.clean_tree()
    segments = b.item_segments(b.read_text(state / "umsetzen.txt"))
    groups, foreign = group(base, ids)
    empty = [sha for shas in groups.values() for sha in shas if not files_of(sha)]
    groups = {t: [sha for sha in shas if sha not in empty] for t, shas in groups.items()}
    results = {t: {"status": None, "reason": None, "commits": groups[t], "files": [], "lines": 0} for t in ids}
    ordered = []
    for todo_id in ids:
        shas = groups[todo_id]
        if not shas:
            reason = (b.last_marker(segments.get(todo_id, ""), "BATCH_SKIP:")
                      or ("nur leere Commits" if any(todo_of(sha, {todo_id}) for sha in empty) else "kein Commit"))
            results[todo_id].update(status="skipped", reason=b.plain(reason, 300))
            continue
        files = sorted({f for sha in shas for f in files_of(sha)})
        lines = sum(lines_of(sha) for sha in shas)
        results[todo_id].update(files=files, lines=lines)
        problem = guard(files, lines, forbidden, max_lines)
        if problem:
            results[todo_id].update(status="dropped", reason=problem)
        else:
            ordered.append((todo_id, shas))
    if foreign or empty or any(r["status"] == "dropped" for r in results.values()):
        kept = rebuild(base, ordered, results)
        regrouped, _ = group(base, ids)
        ordered = [(t, regrouped[t]) for t in kept]
    gate_lines: list[str] = []
    retried: list[str] = []
    if ordered:
        ok, gate_lines = run_gates(scope_for(changed_files(base), cfg), f"{base}..HEAD", cfg, "gesamt")
        if ok:
            retried = flaky(gate_lines)
            for todo_id, _ in ordered:
                results[todo_id].update(status="accepted")
        else:
            retried = linear(base, ordered, cfg, results)
    return {"base": base, "order": ids, "todos": results, "foreign": foreign, "empty": empty, "gates": gate_lines,
            "discarded": discarded, "flaky": retried}


# Was ein Modell aus der Formatvorlage abschreibt, statt einen Befund zu nennen
PLACEHOLDERS = {"", "…", "...", "-", "–", "keine", "keiner", "nichts", "none", "n/a", "na", "null"}


def findings(values) -> list[str]:
    items = values if isinstance(values, list) else []
    return [b.plain(x, 300) for x in items if str(x).strip().strip(".").lower() not in PLACEHOLDERS
            and str(x).strip().lower() not in PLACEHOLDERS][:10]


def parse_reviews(text: str) -> dict[str, dict]:
    """Je TODO die letzte REVIEW-Zeile in dessen eigenem foreach-Abschnitt, die auch dessen ID nennt.

    `reason`: die Zeile davor (oder der Text vor `REVIEW:` in derselben Zeile) – dort nennt ein Review ohne
    Urteil seinen Grund."""
    reviews = {}
    for todo_id, segment in b.item_segments(text).items():
        chosen, reason, previous = None, "", ""
        for line in segment.splitlines():
            index = line.find("REVIEW:")
            data = None
            if index != -1:
                try:
                    data = json.loads(line[index + len("REVIEW:"):].strip())
                except json.JSONDecodeError:
                    data = None
            if isinstance(data, dict) and str(data.get("id", "")).lower() == todo_id:
                chosen, reason = data, line[:index].strip() or previous
            if line.strip():
                previous = line.strip()
        if chosen is None:
            continue
        entry = {key: findings(chosen.get(key)) for key in ("blocker", "major", "minor")}
        entry["verdict"] = b.plain(chosen.get("verdict", ""), 20)
        entry["reason"] = b.plain(reason, 200)
        reviews[todo_id] = entry
    return reviews


def after_review(state: Path, cfg: GateConfig) -> dict:
    data = b.read_json(state / "results.json", None)
    if data is None:
        raise b.BatchError("results.json fehlt – erst prüfen")
    data["discarded"] = sorted(set(data.get("discarded") or []) | set(b.clean_tree()))
    reviews = parse_reviews(b.read_text(state / "review.txt"))
    b.write_json(state / "reviews.json", reviews)
    results = data["todos"]
    accepted = [t for t in data["order"] if results[t]["status"] == "accepted"]
    blocked = [t for t in accepted if reviews.get(t, {}).get("blocker")]
    for todo_id in blocked:
        results[todo_id].update(status="dropped",
                                reason=("Review-Blocker: " + "; ".join(reviews[todo_id]["blocker"]))[:300])
    if blocked:
        retried = linear(data["base"], [(t, results[t]["commits"]) for t in accepted if t not in blocked], cfg, results)
        data["flaky"] = sorted(set(data.get("flaky") or []) | set(retried))
    return data


def finish(state: Path, data: dict) -> None:
    order, results = data["order"], data["todos"]
    regrouped, _ = group(data["base"], order)
    for todo_id in order:
        if results[todo_id]["status"] == "accepted":
            results[todo_id]["commits"] = regrouped[todo_id]
    b.write_json(state / "results.json", data)
    (state / "accepted.txt").write_text("".join(f"{t}\n" for t in order if results[t]["status"] == "accepted"),
                                        encoding="utf-8")
    (state / "head.sha").write_text(b.git("rev-parse", "HEAD").strip(), encoding="utf-8")
    counts = {s: sum(1 for t in order if results[t]["status"] == s) for s in ("accepted", "dropped", "skipped")}
    print(f"Geprüft: {counts['accepted']} angenommen, {counts['dropped']} herausgefallen, "
          f"{counts['skipped']} übersprungen"
          + (f", {len(data['foreign'])} Commit(s) ohne Trailer entfernt" if data.get("foreign") else "")
          + (f", {len(data['empty'])} leere(r) Commit(s) entfernt" if data.get("empty") else "")
          + (f", erst im zweiten Versuch grün: {', '.join(data['flaky'])}" if data.get("flaky") else "")
          + (f", {len(data['discarded'])} nicht committete Datei(en) verworfen" if data.get("discarded") else ""))
    for todo_id in order:
        r = results[todo_id]
        print(f"  {b.short(todo_id)} {r['status']}" + (f" – {b.plain(r['reason'], 160)}" if r.get("reason") else ""))


def draft(state: Path, data: dict) -> None:
    titles = b.read_json(state / "titles.json", {})
    reviews = b.read_json(state / "reviews.json", {})
    rows = []
    for todo_id in data["order"]:
        r, review = data["todos"][todo_id], reviews.get(todo_id, {})
        hints = (f"Review: {len(review.get('major', []))} wichtig, {len(review.get('minor', []))} klein"
                 if b.reviewed(review) else f"kein Review (Urteil: {review.get('verdict') or '–'})" if review
                 else "kein Review")
        rows.append(f"- {b.short(todo_id)} {b.plain(titles.get(todo_id, ''), 120)}: {r['status']}"
                    + (f" ({b.plain(r['reason'], 160)})" if r.get("reason") else "") + f" · {hints}")
    (state / "report-draft.md").write_text("# Entwurf Batch-Bericht\n\n" + "\n".join(rows) + "\n", encoding="utf-8")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True)
    parser.add_argument("--after-review", action="store_true")
    parser.add_argument("--forbidden", default="")
    parser.add_argument("--max-lines", type=int, default=800)
    GateConfig.add_arguments(parser)
    args = parser.parse_args(argv)
    state = b.state(args.state)
    cfg = GateConfig.from_args(args)
    try:
        b.require_isolated(state)
        data = after_review(state, cfg) if args.after_review else initial(state, cfg, args.forbidden.split(), args.max_lines)
    except b.BatchError as error:
        print(f"Prüfung nicht möglich: {error}")
        return 2
    finish(state, data)
    if args.after_review:
        draft(state, data)
    return 0


if __name__ == "__main__":
    sys.exit(main())
