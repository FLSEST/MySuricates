#!/usr/bin/env python3
"""Strip internal TODO-Kurzel-Klammern aus Commit-Betreffzeilen fuer oeffentliche Release-Notizen
(scripts/release-notes.sh, TODO 3c75962d).

Drei Formen kommen in diesem Repo vor:
  1. Eine abschliessende Klammer, deren Inhalt mit einem 8-stelligen Hex-Kuerzel beginnt — egal ob
     danach ein Titel, ein Komma, weitere Kuerzel oder sogar verschachtelte Klammern folgen (ein
     Titel darf selbst Klammern enthalten, z.B. "(94950fbc LearningService … (448 statt … Zeilen))").
     Diese Klammer wird komplett entfernt, balanciert von aussen nach innen gezaehlt.
  2. Eine abschliessende Klammer, deren letztes (durch Komma auf oberster Ebene getrenntes) Element
     ein reines Kuerzel ist, auch wenn davor Fliesstext steht — z.B. "(Stand 2026-09-22, 8f28fad0)".
  3. Eine Klammer *irgendwo* im Betreff, deren Inhalt NUR aus Kuerzeln besteht (optional mit
     fuehrendem Wort "TODO"), z.B. "(TODO 2738fda0)" oder "(a4644103, e8a4ebb4)" — auch wenn danach
     noch Text im Betreff folgt (Merge-Betreffe wie "Merge fix/x (5bdf5332) — Text").
Eine normale Klammer ohne Kuerzel (kein 8-Hex-Token als erstes/letztes/einziges Element) bleibt stehen.

Usage: liest Zeilen von stdin, schreibt die bereinigten Zeilen nach stdout (Reihenfolge erhalten).
"""
from __future__ import annotations

import re
import sys

HEX8 = r"[0-9a-f]{8}"
# Faengt eine Klammer, deren Inhalt mit einem 8-Hex-Kuerzel beginnt (optional "TODO " davor),
# unabhaengig davon, was danach kommt.
_TRAILING_START_RE = re.compile(rf"^(TODO\s+)?{HEX8}(?![0-9a-f])")
# Ein einzelnes Kuerzel-Token, optional mit "TODO "-Praefix, fuer die reine Kuerzelliste.
_CODE_TOKEN_RE = re.compile(rf"^(TODO\s+)?{HEX8}$")


def _find_matching_open(s: str, close_idx: int) -> int | None:
    """Index der zu s[close_idx] (==')') gehoerenden oeffnenden Klammer, oder None."""
    depth = 0
    for i in range(close_idx, -1, -1):
        c = s[i]
        if c == ")":
            depth += 1
        elif c == "(":
            depth -= 1
            if depth == 0:
                return i
    return None


def _split_top_level_commas(s: str) -> list[str]:
    """Wie str.split(','), aber Kommas innerhalb verschachtelter Klammern zaehlen nicht."""
    parts: list[str] = []
    depth = 0
    current: list[str] = []
    for c in s:
        if c == "(":
            depth += 1
            current.append(c)
        elif c == ")":
            depth -= 1
            current.append(c)
        elif c == "," and depth == 0:
            parts.append("".join(current))
            current = []
        else:
            current.append(c)
    parts.append("".join(current))
    return parts


def _ends_with_code_token(inner: str) -> bool:
    """True, wenn das letzte (oberste-Ebene-Komma-getrennte) Element ein reines Kuerzel ist —
    z.B. "Stand 2026-09-22, 8f28fad0": Fliesstext vor dem Kuerzel ist hier egal, nur das letzte
    Element muss ein Kuerzel sein."""
    parts = _split_top_level_commas(inner)
    if not parts:
        return False
    return bool(_CODE_TOKEN_RE.match(parts[-1].strip()))


def _strip_trailing_code_bracket(s: str) -> str:
    """Entfernt eine abschliessende, balanciert geklammerte Gruppe, wenn ihr Inhalt mit einem
    8-Hex-Kuerzel beginnt (Titel/Kommas/verschachtelte Klammern danach sind egal) oder wenn ihr
    letztes Komma-Element auf oberster Ebene ein reines Kuerzel ist."""
    s = s.rstrip()
    if not s.endswith(")"):
        return s
    open_idx = _find_matching_open(s, len(s) - 1)
    if open_idx is None:
        return s
    inner = s[open_idx + 1 : -1]
    if _TRAILING_START_RE.match(inner.strip()) or _ends_with_code_token(inner):
        return s[:open_idx].rstrip()
    return s


def _is_pure_code_list(inner: str) -> bool:
    """True, wenn eine (nicht verschachtelte) Klammer nur aus Kuerzeln besteht — z.B.
    "TODO 2738fda0" oder "cc0eb3ba, 4a2edda6, 46e394e0"."""
    parts = [p.strip() for p in inner.split(",")]
    if not parts or not all(parts):
        return False
    return all(_CODE_TOKEN_RE.match(p) for p in parts)


def _strip_inline_code_brackets(s: str) -> str:
    """Entfernt reine Kuerzel-Klammern irgendwo im Betreff (nicht verschachtelt — fuer den
    Titel-Fall sorgt bereits _strip_trailing_code_bracket)."""

    def repl(m: re.Match) -> str:
        return "" if _is_pure_code_list(m.group(1)) else m.group(0)

    return re.sub(r"\(([^()]*)\)", repl, s)


def clean_subject(subject: str) -> str:
    s = _strip_trailing_code_bracket(subject)
    s = _strip_inline_code_brackets(s)
    # Doppelte/Rand-Leerzeichen, die durchs Entfernen entstehen (z.B. vor " — Text" oder am Ende).
    s = re.sub(r"[ \t]+", " ", s)
    s = re.sub(r"[ \t]+([,.;:])", r"\1", s)
    return s.strip()


def main() -> None:
    for line in sys.stdin:
        line = line.rstrip("\n")
        if line == "":
            print(line)
            continue
        print(clean_subject(line))


if __name__ == "__main__":
    main()
