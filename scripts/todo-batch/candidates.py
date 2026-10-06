#!/usr/bin/env python3
"""TODO batch, triage 3 (TODO cc0eb3ba): pre-filter candidates for the triage – without AI.

Candidate: status OPEN, no `batch:*` tag, no open question. While a batch is still running (IN_PROGRESS with
`batch:selected` and `batch:started`, not stuck), there are none; manually selected ones count against `--batch-max`. `<state>/candidates.json` only exists with candidates – the triage's
`run_if` depends on it. Output: the candidates as one JSON line for the prompt.

Plus `near_limit` (TODO 8bbc2368): files that are at most `--size-margin` lines below their ceiling
(or above it). `--size-limits <limits file> <source root>`: the limits live in the limits file in the format of
`SourceSizeRatchetTest` (`MAX_LINES = N` as default, `entry("<path>", N)` per exception, paths relative to the
source root); the `.java` files under the source root are counted. Without a limits file the list stays empty – a
repo without a size ratchet leaves the two values in the context empty or omits them. If a context value is missing entirely, the
platform leaves the placeholder verbatim (`{{ name }}` → three words); that also counts as "not set".
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
    """(batch running?, candidates, TODOs already selected by hand/earlier and not yet started)."""
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
    """(default ceiling, ceiling per file relative to the source root) from the limits file."""
    found = _MAX_LINES.search(ratchet_text)
    return (int(found.group(1)) if found else DEFAULT_MAX_LINES,
            {path: int(limit) for path, limit in _ENTRY.findall(ratchet_text)})


def near_limit(ratchet: Path, root: Path, margin: int, base: Path | None = None) -> list[dict]:
    """Files the triage should avoid: distance to the ceiling below `margin` lines, tightest first."""
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
    """(limits file, source root) from `--size-limits`; an unresolved placeholder `{{ … }}` counts as empty."""
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
