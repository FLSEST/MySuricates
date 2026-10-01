#!/usr/bin/env python3
"""TODO-Batch, Abarbeiten (TODO d6c0afe6): das Ergebnis des Start-Updates auswerten – ohne KI.

Der `todo-update`-Block `starten` überspringt einen Eintrag, dessen Bedingung nicht mehr passt (etwa ein TODO, das
inzwischen jemand anders in Arbeit hat), und meldet das nur in seiner Ausgabe: `✓ <id8>` je gestartetem TODO,
`– <id8>: <grund>` je übersprungenem. Liest diese Ausgabe aus `<state>/starten.txt`; nur bestätigt gestartete TODOs
bleiben in `ids.txt` und werden abgearbeitet. Die übrigen – auch solche, die die Ausgabe gar nicht nennt, oder alle,
wenn der Block scheiterte – stehen mit Grund in `not_started.json`; der Bericht nennt sie und wird gelb.
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
    """(gestartete IDs in Batch-Reihenfolge, nicht gestartete ID → Grund)."""
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
                reasons[todo_id] = b.plain(skipped.group(2), 200) or "übersprungen"
    if not started and not reasons and (output or "").strip():
        failure = b.plain(output, 200)
        return [], {todo_id: failure for todo_id in ids}
    kept = [t for t in ids if t in started and t not in reasons]
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
