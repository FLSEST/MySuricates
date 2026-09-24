#!/usr/bin/env python3
"""Deterministische Doku-Pruefung: tote Verweise und DE/EN-Gleichstand (TODO c58d7402).

Drei Dinge, die eine Maschine besser prueft als ein Lesedurchgang:

1. **Tote Ziele** — jeder relative Markdown-Link in einer getrackten .md zeigt auf eine
   existierende Datei.
2. **Tote Anker** — das Fragment hinter `#` entspricht einer Ueberschrift der Zieldatei,
   nach den Slug-Regeln von GitHub.
3. **DE/EN-Gleichstand** — jede `docs/*.md` hat ein Gegenstueck unter `docs/en/`, und beide
   fuehren dieselbe Folge von Ueberschriften-Ebenen. Ebenso eine Ebene tiefer fuer die
   Ordner in `MIRRORED_SUBDIRS` (`docs/konzepte/x.md` <-> `docs/en/konzepte/x.md`).

Was hier NICHT geprueft wird, weil es Urteil braucht: Aktualitaet gegenueber dem Code
(dafuer doc-facts-check.sh) und Verstaendlichkeit fuer Fremde (dafuer der Workflow
"Doku-Durchsicht").

Bekannte, ticketierte Luecken stehen in KNOWN_GAPS. Sie machen das Gate nicht rot — eine
*neue* Luecke schon. Ohne diese Liste stuende das Gate dauerhaft auf YELLOW und waere als
Signal wertlos; genau der Fehler, den docs-check.sh bis 2026-09-20 hatte.

Run:  python3 scripts/public-readiness/docs_parity_check.py
      python3 scripts/public-readiness/docs_parity_check.py --root <dir>
Exit: 0 = keine neuen Befunde, 1 = neue Befunde
"""
from __future__ import annotations

import argparse
import difflib
import os
import subprocess
import sys
from pathlib import Path

# Das eigene Verzeichnis muss auf den Pfad: Tests laden dieses Skript ueber
# spec_from_file_location, und dabei liegt sein Verzeichnis nicht auf sys.path.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from md_common import headings, iter_links, slug, tracked_markdown  # noqa: E402

# Unterordner von docs/, die wie docs/ selbst eins zu eins unter docs/en/ gespiegelt werden.
# Bewusst eine Liste statt "was es unter docs/en/ gibt": sonst bliebe ein Ordner, dessen
# englische Fassung ganz fehlt, unsichtbar — so blieb docs/konzepte/ unbemerkt (TODO b94beaed).
MIRRORED_SUBDIRS: tuple[str, ...] = ("onboarding", "konzepte")

# Bekannte, ticketierte Luecken. Format: Pfad -> Begruendung mit TODO-Kennung.
# Zurzeit leer: die zehn Diagramm-Seiten, die hier standen, haben seit TODO 47bd4d27 ein
# englisches Gegenstueck — erzeugt von scripts/docs-diagrams.py aus derselben Quelle.
KNOWN_GAPS: dict[str, str] = {}

# Einzelne Abschnitte ohne Gegenstueck. Format: "datei::ueberschrift" -> TODO-Kennung.
# (Der Eintrag "entwicklung.md::## Bekannte Einschränkungen" ist mit TODO 47bd4d27 entfallen:
# fehlend war in Wahrheit "## Diagramm-Seiten" — der Ebenenvergleich hatte die Luecke nur
# dem letzten gleichrangigen Abschnitt zugeschrieben.)
KNOWN_SECTION_GAPS: dict[str, str] = {
    "architektur.md::## Scheduler: verpasste Termine (Standby und Kaltstart)": "c58d7402",
    "architektur.md::## Completion Triggers (V39)": "c58d7402",
}


def check_links(root: Path, files: list[str]) -> tuple[list[str], int]:
    findings: list[str] = []
    checked = 0
    for rel in files:
        path = root / rel
        if not path.exists():
            continue
        # Welche Links aktiv und relativ sind, entscheidet md_common.iter_links —
        # dieselbe Regel, die der Linkgraph in docs_orphan_scan.py benutzt.
        for no, href in iter_links(path):
            checked += 1
            path_part, _, frag = href.partition("#")
            target = (path.parent / path_part).resolve() if path_part else path.resolve()
            if not target.exists():
                findings.append(f"totes-ziel {rel}:{no} -> {href}")
                continue
            if frag and target.suffix == ".md":
                if frag.lower() not in {slug(h) for h in headings(target)}:
                    findings.append(f"toter-anker {rel}:{no} -> {href}")
    return findings, checked


def check_en_to_de(root: Path, files: list[str]) -> list[str]:
    """Englische Seiten, die auf eine rein deutsche Doku-Seite verweisen (TODO 1b84ecab).

    Englisch sind README.md und alles unter docs/en/. Ein Verweis zaehlt, wenn sein Ziel
    unter docs/ liegt, aber nicht unter docs/en/ — der englische Leser landet dann auf
    Deutsch. Das ist ein Befund fuers Audit, kein Grund fuer ein rotes Gate: die Luecke
    ist bekannt und steht als Uebersetzungsarbeit an, nicht als Fehler im Commit.
    """
    docs_dir, en_dir = (root / "docs").resolve(), (root / "docs" / "en").resolve()
    hits: list[str] = []
    for rel in files:
        if rel != "README.md" and not rel.startswith("docs/en/"):
            continue
        path = root / rel
        if not path.exists():
            continue
        for no, href in iter_links(path):
            path_part = href.partition("#")[0]
            if not path_part:
                continue
            target = (path.parent / path_part).resolve()
            if not target.exists() or not target.is_relative_to(docs_dir) or target.is_relative_to(en_dir):
                continue
            # Nur Seiten sind deutsch oder englisch; Messdaten (JSON), Bilder u. ae. sind
            # sprachneutral und haben kein Gegenstueck zu brauchen (TODO b94beaed).
            if target.suffix != ".md":
                continue
            hits.append(f"en-verweist-auf-deutsch {rel}:{no} -> {target.relative_to(root.resolve()).as_posix()}")
    return hits


def check_parity(root: Path) -> tuple[list[str], list[str]]:
    """Liefert (neue Befunde, bekannte Luecken) fuer docs/ und MIRRORED_SUBDIRS."""
    if not (root / "docs").is_dir():
        return [], []
    new: list[str] = []
    known: list[str] = []
    for sub in ("", *MIRRORED_SUBDIRS):
        _check_dir_pair(root, sub, new, known)
    return new, known


def _check_dir_pair(root: Path, sub: str, new: list[str], known: list[str]) -> None:
    """Vergleicht docs/<sub>/ mit docs/en/<sub>/: Dateien und Folge der Ueberschriften-Ebenen.

    Schluessel fuer KNOWN_GAPS sind `docs/<sub>/<datei>`, fuer KNOWN_SECTION_GAPS
    `<sub>/<datei>::<ueberschrift>` (im obersten Ordner ohne `<sub>/`).
    """
    de_dir = root / "docs" / sub if sub else root / "docs"
    en_dir = root / "docs" / "en" / sub if sub else root / "docs" / "en"
    if not de_dir.is_dir():
        return
    prefix = f"{sub}/" if sub else ""
    de_names = {p.name for p in de_dir.glob("*.md")}
    en_names = {p.name for p in en_dir.glob("*.md")} if en_dir.is_dir() else set()

    for name in sorted(de_names - en_names):
        key = f"docs/{prefix}{name}"
        if key in KNOWN_GAPS:
            known.append(f"kein-en-gegenstueck {key} (bekannt, TODO {KNOWN_GAPS[key]})")
        else:
            new.append(f"kein-en-gegenstueck {key}")
    for name in sorted(en_names - de_names):
        new.append(f"nur-englisch docs/en/{prefix}{name}")

    for name in sorted(de_names & en_names):
        de, en = headings(de_dir / name), headings(en_dir / name)
        if len(de) == len(en):
            continue
        levels_de = [h.split(" ")[0] for h in de]
        levels_en = [h.split(" ")[0] for h in en]
        label = f"{prefix}{name}"
        for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, levels_de, levels_en).get_opcodes():
            if tag == "equal":
                continue
            for k in range(i1, i2):
                key = f"{label}::{de[k]}"
                if key in KNOWN_SECTION_GAPS:
                    known.append(f"abschnitt-fehlt-en {label} '{de[k]}' (bekannt, TODO {KNOWN_SECTION_GAPS[key]})")
                else:
                    new.append(f"abschnitt-fehlt-en {label} '{de[k]}'")
            for k in range(j1, j2):
                new.append(f"abschnitt-nur-en {label} '{en[k]}'")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=None)
    args = parser.parse_args()
    root = Path(args.root).resolve() if args.root else Path(
        subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True,
                       text=True, check=False).stdout.strip() or "."
    ).resolve()

    files = tracked_markdown(root)
    link_findings, checked = check_links(root, files)
    parity_new, parity_known = check_parity(root)
    en_to_de = check_en_to_de(root, files)
    findings = link_findings + parity_new

    print("## Doku-Verweise und DE/EN-Gleichstand")
    print(f"markdown_files: {len(files)}")
    print(f"relative_links_checked: {checked}")
    print(f"findings: {len(findings)}")
    print(f"known_gaps: {len(parity_known)}")
    for item in parity_known:
        print(f"bekannt: {item}")
    for item in findings:
        print(f"BEFUND: {item}")
    # Nur gemeldet, nicht gewertet: siehe check_en_to_de.
    print(f"en_links_to_german: {len(en_to_de)}")
    for item in en_to_de:
        print(f"HINWEIS: {item}")

    print()
    print("## Gate status")
    if findings:
        print("gate_status: YELLOW")
        print("gate_category: documentation-links")
        print("gate_description: Tote Verweise oder neue DE/EN-Luecken in der Doku.")
        print("todo_item: Tote Verweise oder DE/EN-Luecken in der Doku beheben | MEDIUM |"
              " documentation,release | docs_parity_check.py meldet neue Befunde.")
        return 1
    print("gate_status: GREEN")
    print("gate_category: documentation-links")
    print("gate_description: Keine toten Verweise, keine neuen DE/EN-Luecken.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
