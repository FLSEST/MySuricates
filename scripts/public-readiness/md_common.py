#!/usr/bin/env python3
"""Gemeinsame Markdown-Bausteine der Doku-Pruefungen.

Hier steht nur, was mehr als ein Pruefskript braucht: die Slug-Regel, die
fence-bewusste Ueberschriften- und Link-Erkennung, die Dateiliste und die
DE/EN-Paarzuordnung. Herausgeloest aus `docs_parity_check.py`, als
`docs_orphan_scan.py` denselben Linkbegriff brauchte — zwei Fassungen
derselben Regel waeren die sicherste Art, dass die Gates sich eines Tages
widersprechen.

Importiert wird mit dem Verzeichnis auf `sys.path`, weil beide Skripte in
Tests ueber `spec_from_file_location` geladen werden und dabei ihr eigenes
Verzeichnis *nicht* auf dem Pfad liegt:

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

#: Praefix der englischen Spiegelung unterhalb von docs/.
MIRROR_PREFIX = "docs/en/"
DOCS_PREFIX = "docs/"


def slug(heading: str) -> str:
    """Anker-Slug nach GitHubs Regeln.

    Kleinschreibung, alles ausser Buchstaben/Ziffern/Unterstrich/Bindestrich/Leerzeichen
    faellt weg, dann wird **jedes einzelne** Leerzeichen zu einem Bindestrich. Das letzte
    Detail ist der Fallstrick: Wer Leerzeichenfolgen zu einem Bindestrich zusammenzieht,
    haelt korrekte Anker faelschlich fuer tot (passiert bei der Erstfassung dieses Skripts).
    """
    text = heading.lstrip("#").strip().lower()
    text = re.sub(r"[^\w\- ]", "", text, flags=re.UNICODE)
    return text.replace(" ", "-")


def headings(path: Path) -> list[str]:
    """Ueberschriften einer Datei, ohne die in Code-Bloecken.

    Ein `# Kommentar` in einem Shell-Beispiel ist keine Ueberschrift — ohne die
    Fence-Erkennung zaehlt er mit und erzeugt einen Phantom-Unterschied.
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
    """Aktive relative Markdown-Links einer Datei als (Zeilennummer, Ziel).

    Ausgelassen wird, was kein Verweis auf eine Datei im Repository ist: Links in
    Code-Bloecken, auskommentierte Zeilen (die Screenshot-Platzhalter in beiden READMEs
    stehen genau so da), externe Ziele und Routen-Vorlagen wie `/workflows/<id>`.
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
    """Der jeweils andere Pfad eines DE/EN-Paares, oder None ausserhalb von docs/.

    `docs/x.md` <-> `docs/en/x.md`, und ebenso eine Ebene tiefer:
    `docs/onboarding/FAQ.md` <-> `docs/en/onboarding/FAQ.md`. Die flache Zuordnung
    allein wuerde die gespiegelten Unterverzeichnisse uebersehen.
    """
    if rel.startswith(MIRROR_PREFIX):
        return DOCS_PREFIX + rel[len(MIRROR_PREFIX):]
    if rel.startswith(DOCS_PREFIX):
        return MIRROR_PREFIX + rel[len(DOCS_PREFIX):]
    return None


def to_de_path(rel: str) -> str:
    """Faltet die englische Spiegelung auf den deutschen Pfad.

    Die Identitaet eines Findings darf nicht davon abhaengen, ob es gerade eine
    Uebersetzung gibt: `docs/en/x.md` und `docs/x.md` sind dasselbe Dokument.
    """
    if rel.startswith(MIRROR_PREFIX):
        return DOCS_PREFIX + rel[len(MIRROR_PREFIX):]
    return rel
