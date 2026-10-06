#!/usr/bin/env python3
"""Strip internal TODO short-ID brackets from commit subject lines for public release notes
(scripts/release-notes.sh, TODO 3c75962d).

Three forms occur in this repo:
  1. A trailing bracket whose content starts with an 8-digit hex short ID — no matter whether
     a title, a comma, further short IDs or even nested brackets follow (a
     title may itself contain brackets, e.g. "(94950fbc LearningService … (448 instead of … lines))").
     This bracket is removed completely, counted balanced from the outside in.
  2. A trailing bracket whose last element (separated by a top-level comma)
     is a pure short ID, even if running text precedes it — e.g. "(Stand 2026-09-22, 8f28fad0)".
  3. A bracket *anywhere* in the subject whose content consists ONLY of short IDs (optionally with
     a leading word "TODO"), e.g. "(TODO 2738fda0)" or "(a4644103, e8a4ebb4)" — even if
     text follows in the subject (merge subjects like "Merge fix/x (5bdf5332) — Text").
A normal bracket without a short ID (no 8-hex token as first/last/only element) stays.

Usage: reads lines from stdin, writes the cleaned lines to stdout (order preserved).
"""
from __future__ import annotations

import re
import sys

HEX8 = r"[0-9a-f]{8}"
# Catches a bracket whose content starts with an 8-hex short ID (optionally preceded by "TODO "),
# regardless of what follows.
_TRAILING_START_RE = re.compile(rf"^(TODO\s+)?{HEX8}(?![0-9a-f])")
# A single short-ID token, optionally with a "TODO " prefix, for the pure short-ID list.
_CODE_TOKEN_RE = re.compile(rf"^(TODO\s+)?{HEX8}$")


def _find_matching_open(s: str, close_idx: int) -> int | None:
    """Index of the opening bracket belonging to s[close_idx] (==')'), or None."""
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
    """Like str.split(','), but commas inside nested brackets do not count."""
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
    """True if the last (top-level comma-separated) element is a pure short ID —
    e.g. "Stand 2026-09-22, 8f28fad0": running text before the short ID does not matter here, only the last
    element must be a short ID."""
    parts = _split_top_level_commas(inner)
    if not parts:
        return False
    return bool(_CODE_TOKEN_RE.match(parts[-1].strip()))


def _strip_trailing_code_bracket(s: str) -> str:
    """Removes a trailing, balanced bracketed group if its content starts with an
    8-hex short ID (title/commas/nested brackets after it do not matter) or if its
    last top-level comma element is a pure short ID."""
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
    """True if a (non-nested) bracket consists only of short IDs — e.g.
    "TODO 2738fda0" or "cc0eb3ba, 4a2edda6, 46e394e0"."""
    parts = [p.strip() for p in inner.split(",")]
    if not parts or not all(parts):
        return False
    return all(_CODE_TOKEN_RE.match(p) for p in parts)


def _strip_inline_code_brackets(s: str) -> str:
    """Removes pure short-ID brackets anywhere in the subject (not nested — the
    title case is already handled by _strip_trailing_code_bracket)."""

    def repl(m: re.Match) -> str:
        return "" if _is_pure_code_list(m.group(1)) else m.group(0)

    return re.sub(r"\(([^()]*)\)", repl, s)


def clean_subject(subject: str) -> str:
    s = _strip_trailing_code_bracket(subject)
    s = _strip_inline_code_brackets(s)
    # Double/edge spaces that arise from the removal (e.g. before " — Text" or at the end).
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
