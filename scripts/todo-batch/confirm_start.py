#!/usr/bin/env python3
"""TODO batch, processing (TODO d6c0afe6): evaluate the result of the start update – without AI.

The `todo-update` block `starten` skips an entry whose condition no longer holds (e.g. a TODO that
someone else has meanwhile taken on), and reports that only in its output: `✓ <id8>` per started TODO,
`– <id8>: <reason>` per skipped one. Each TODO has two entries in the update (prepare.py): the start and a
confirmation that only applies to an already started batch TODO – one `✓` is enough for it to count as started
(also when the same run is resumed). Reads that output from `<state>/starten.txt`; only TODOs confirmed as started
stay in `ids.txt` and get processed. The rest – including ones the output does not mention at all, or all of them
if the block failed – go into `not_started.json` with a reason; the report names them and turns yellow.
"""
from __future__ import annotations

import argparse
import re
import sys

import batchlib as b

_STARTED = re.compile(r"^✓ ([0-9a-fA-F]{8})\s*$")
_SKIPPED = re.compile(r"^[–-] ([0-9a-fA-F]{8}): ?(.*)$")
NO_RESULT = "Start-Update ohne Ergebnis für dieses TODO"


def confirm(ids: list[str], output: str) -> tuple[list[str], dict[str, str]]:
    """(started IDs in batch order, not-started ID → reason)."""
    by_short: dict[str, list[str]] = {}
    for todo_id in ids:
        by_short.setdefault(b.short(todo_id).lower(), []).append(todo_id)
    started, reasons = set(), {}
    for line in (output or "").splitlines():
        line = line.strip()
        done, skipped = _STARTED.match(line), _SKIPPED.match(line)
        if done:
            started.update(by_short.get(done.group(1).lower(), []))
        elif skipped:
            for todo_id in by_short.get(skipped.group(1).lower(), []):
                reasons.setdefault(todo_id, b.plain(skipped.group(2), 200) or "übersprungen")
    if not started and not reasons and (output or "").strip():
        failure = b.plain(output, 200)
        return [], {todo_id: failure for todo_id in ids}
    kept = [t for t in ids if t in started]
    return kept, {t: reasons.get(t, NO_RESULT) for t in ids if t not in kept}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True)
    args = parser.parse_args(argv)
    state = b.state(args.state)
    ids = [line.strip() for line in b.read_text(state / "ids.txt").splitlines() if b.is_uuid(line.strip())]
    kept, not_started = confirm(ids, b.read_text(state / "starten.txt"))
    (state / "ids.txt").write_text("".join(f"{t}\n" for t in kept), encoding="utf-8")
    b.write_json(state / "not_started.json", not_started)
    print(f"Gestartet: {len(kept)} von {len(ids)} TODO(s)" + (f", nicht gestartet: {len(not_started)}" if not_started else ""))
    for todo_id, why in not_started.items():
        print(f"  {b.short(todo_id)} – {why}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
