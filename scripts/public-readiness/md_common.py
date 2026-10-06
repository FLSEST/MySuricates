#!/usr/bin/env python3
"""Shared Markdown building blocks of the docs checks.

Only what more than one check script needs lives here: the slug rule, the
fence-aware heading and link detection, the file list and the
DE/EN pair mapping. Extracted from `docs_parity_check.py` when
`docs_orphan_scan.py` needed the same notion of a link — two versions
of the same rule would be the surest way for the gates to contradict
each other one day.

Imported with the directory on `sys.path`, because both scripts are loaded in
tests via `spec_from_file_location`, and their own
directory is *not* on the path then:

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from md_common import slug, headings, tracked_markdown
"""
from __future__ import annotations

import re
import subprocess
from pathlib import Path
from typing import Iterator

LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
FENCE = re.compile(r"^\s*(?:```|~~~)")

#: Prefix of the English mirror below docs/.
MIRROR_PREFIX = "docs/en/"
DOCS_PREFIX = "docs/"


def slug(heading: str) -> str:
    """Anchor slug following GitHub's rules.

    Lowercase, everything except letters/digits/underscore/hyphen/space
    is dropped, then **every single** space becomes a hyphen. That last
    detail is the pitfall: collapsing runs of spaces into one hyphen
    wrongly treats correct anchors as dead (happened in the first version of this script).
    """
    text = heading.lstrip("#").strip().lower()
    text = re.sub(r"[^\w\- ]", "", text, flags=re.UNICODE)
    return text.replace(" ", "-")


def headings(path: Path) -> list[str]:
    """Headings of a file, excluding those in code blocks.

    A `# Kommentar` in a shell example is not a heading — without the
    fence detection it counts and creates a phantom difference.
    """
    out: list[str] = []
    fence = False
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = line.lstrip()
        if FENCE.match(line):
            fence = not fence
            continue
        if not fence and stripped.startswith("#") and not stripped.startswith("#!"):
            out.append(stripped)
    return out


def iter_links(path: Path) -> Iterator[tuple[int, str]]:
    """Active relative Markdown links of a file as (line number, target).

    Skipped is whatever is not a reference to a file in the repository: links in
    code blocks, commented-out lines (the screenshot placeholders in both READMEs
    look exactly like that), external targets and route templates like `/workflows/<id>`.
    """
    fence = False
    for no, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        if FENCE.match(line):
            fence = not fence
            continue
        if fence:
            continue
        if "<!--" in line and "-->" in line:
            continue
        for href in LINK.findall(line):
            if href.startswith(("http://", "https://", "mailto:", "#")):
                continue
            if "<" in href or ">" in href:
                continue
            yield no, href


def tracked_markdown(root: Path) -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "*.md"], cwd=root, capture_output=True, text=True, check=False
    )
    if result.returncode == 0 and result.stdout.strip():
        return sorted(result.stdout.split())
    return sorted(p.relative_to(root).as_posix() for p in root.rglob("*.md"))


def mirror_pair(rel: str) -> str | None:
    """The respective other path of a DE/EN pair, or None outside docs/.

    `docs/x.md` <-> `docs/en/x.md`, and likewise one level deeper:
    `docs/onboarding/FAQ.md` <-> `docs/en/onboarding/FAQ.md`. The flat mapping
    alone would miss the mirrored subdirectories.
    """
    if rel.startswith(MIRROR_PREFIX):
        return DOCS_PREFIX + rel[len(MIRROR_PREFIX):]
    if rel.startswith(DOCS_PREFIX):
        return MIRROR_PREFIX + rel[len(DOCS_PREFIX):]
    return None


def to_de_path(rel: str) -> str:
    """Folds the English mirror onto the German path.

    The identity of a finding must not depend on whether a
    translation currently exists: `docs/en/x.md` and `docs/x.md` are the same document.
    """
    if rel.startswith(MIRROR_PREFIX):
        return DOCS_PREFIX + rel[len(MIRROR_PREFIX):]
    return rel
