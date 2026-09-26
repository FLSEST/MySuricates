#!/usr/bin/env python3
"""Deterministische Doku-Pruefung: tote Verweise und DE/EN-Gleichstand (TODO c58d7402).

Drei Dinge, die eine Maschine besser prueft als ein Lesedurchgang:

1. **Tote Ziele** — jeder relative Markdown-Link in einer getrackten .md zeigt auf eine
   existierende Datei.
2. **Tote Anker** — das Fragment hinter `#` entspricht einer Ueberschrift der Zieldatei,
   nach den Slug-Regeln von GitHub.
3. **DE/EN-Gleichstand** — jede `docs/*.md` hat ein Gegenstueck unter `docs/en/`, und beide
   fuehren dieselbe Folge von Ueberschriften-Ebenen. Ebenso eine Ebene tiefer fuer die
   Ordner in `MIRRORED_SUBDIRS` (`docs/konzepte/x.md` <-> `docs/en/konzepte/x.md`). Weicht die
   Folge ab, paart `align_sections` die Abschnitte ueber ihren Inhalt und nennt genau die ohne
   Gegenstueck (TODO 2d553479).

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
import re
import subprocess
import sys
from pathlib import Path

# Das eigene Verzeichnis muss auf den Pfad: Tests laden dieses Skript ueber
# spec_from_file_location, und dabei liegt sein Verzeichnis nicht auf sys.path.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from md_common import FENCE, headings, iter_links, slug, tracked_markdown  # noqa: E402

# Unterordner von docs/, die wie docs/ selbst eins zu eins unter docs/en/ gespiegelt werden.
# Bewusst eine Liste statt "was es unter docs/en/ gibt": sonst bliebe ein Ordner, dessen
# englische Fassung ganz fehlt, unsichtbar — so blieb docs/konzepte/ unbemerkt (TODO b94beaed).
MIRRORED_SUBDIRS: tuple[str, ...] = ("onboarding", "konzepte")

# Bekannte, ticketierte Luecken. Format: Pfad -> Begruendung mit TODO-Kennung.
# Zurzeit leer: die zehn Diagramm-Seiten, die hier standen, haben seit TODO 47bd4d27 ein
# englisches Gegenstueck — erzeugt von scripts/docs-diagrams.py aus derselben Quelle.
KNOWN_GAPS: dict[str, str] = {}

# Einzelne Abschnitte ohne Gegenstueck. Format: "datei::ueberschrift" -> TODO-Kennung.
# Der Schluessel ist die Ueberschrift, die WIRKLICH fehlt: Bis TODO 2d553479 verglich die Pruefung
# nur die Folge der Ebenen und schrieb eine Luecke dem letzten gleichrangigen Abschnitt zu. So
# standen hier "entwicklung.md::## Bekannte Einschränkungen" (fehlend war "## Diagramm-Seiten")
# und "architektur.md::## Completion Triggers (V39)" (fehlend war das Audit Center, seit TODO
# 49f15990 uebersetzt). Zurzeit leer: "## Scheduler: verpasste Termine" ist seit TODO c58d7402
# uebersetzt.
KNOWN_SECTION_GAPS: dict[str, str] = {}


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
        de, en = sections(de_dir / name), sections(en_dir / name)
        # Gleiche Ebenenfolge: nichts zu paaren. Ob eine Ueberschrift uebersetzt oder durch eine
        # andere ersetzt ist, laesst sich am Text allein nicht sicher sagen.
        if [_level(h) for h, _ in de] == [_level(h) for h, _ in en]:
            continue
        label = f"{prefix}{name}"
        only_de, only_en = align_sections(de, en)
        for k in only_de:
            heading = de[k][0]
            key = f"{label}::{heading}"
            if key in KNOWN_SECTION_GAPS:
                known.append(f"abschnitt-fehlt-en {label} '{heading}' (bekannt, TODO {KNOWN_SECTION_GAPS[key]})")
            else:
                new.append(f"abschnitt-fehlt-en {label} '{heading}'{_position(de, k)}")
        for k in only_en:
            new.append(f"abschnitt-nur-en {label} '{en[k][0]}'{_position(en, k)}")


# ---------------------------------------------------------------------------
# Abschnitte paaren (TODO 2d553479)
#
# Die Erstfassung verglich nur die Folge der Ebenen (difflib ueber "##", "###" …). Fehlt ein
# "##"-Abschnitt, ist das mehrdeutig -- jeder gleichrangige Abschnitt koennte fehlen --, und
# difflib nahm stets den letzten. Zweimal stand deshalb der falsche Abschnitt in
# KNOWN_SECTION_GAPS (siehe dort).
#
# Jetzt werden die Abschnitte ueber ihren Inhalt gepaart: eine Ausrichtung (dynamische
# Programmierung) ueber beide Folgen, die die Reihenfolge wahrt, nur gleiche Ebenen paart und die
# Summe der Aehnlichkeiten maximiert. Aehnlich heisst:
#
# - die Ueberschrift -- gleiche Woerter ("Workflow Chains", "(V39)", `goals`) oder ein Kognat
#   ("Architektur"/"Architecture");
# - oder der Abschnittstext -- sprachneutrale Zeichen darin: Code in Backticks, Linkziele,
#   Bezeichner mit Ziffer, Unterstrich, Punkt, Schraegstrich oder Binnenmajuskel. Die stehen in
#   beiden Sprachen gleich da, auch wenn die Ueberschrift ganz uebersetzt ist.
#
# Jedes Paar bringt dazu einen kleinen Grundwert (PAIR_BASE), damit zwei Abschnitte ohne jede
# Aehnlichkeit trotzdem lieber gepaart werden als beide als Luecke zu gelten. Er ist klein genug,
# dass eine echte Aehnlichkeit eine Verschiebung ueberstimmt. Ohne jedes Signal (reine Prosa, ganz
# uebersetzte Ueberschriften) bleibt die Wahl eine Vermutung -- dann gilt, wie frueher, der
# spaetere Abschnitt als fehlend. Probe am Bestand 2026-09-26: je Datei mit gleicher Ebenenfolge
# jeden englischen Abschnitt einzeln entfernt; richtig benannt wurden 1058 von 1086 Luecken
# (Ebenenfolge allein: 248). Die verfehlten sind reine Prosa (docs/konzepte: "Wofuer taugt das?"
# neben "Vorteile") oder fast gleiche Nachbarn (zwei Endpunkte derselben Ressource).
# ---------------------------------------------------------------------------
PAIR_BASE = 0.1
_HEADING_WORD = re.compile(r"[a-z0-9_.]+")
_CODE_SPAN = re.compile(r"`([^`\n]+)`")
_LINK_TARGET = re.compile(r"\]\(([^)\s]+)\)")
_TOKEN = re.compile(r"[A-Za-z0-9_./:-]+")
_NEUTRAL = re.compile(r"[0-9_/]|[a-z][A-Z]|\.[a-z]")


def sections(path: Path) -> list[tuple[str, str]]:
    """(Ueberschrift, Abschnittstext bis zur naechsten Ueberschrift) -- fence-bewusst wie headings()."""
    out: list[tuple[str, list[str]]] = []
    fence = False
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        stripped = line.lstrip()
        if FENCE.match(line):
            fence = not fence
        elif not fence and stripped.startswith("#") and not stripped.startswith("#!"):
            out.append((stripped, []))
            continue
        if out:
            out[-1][1].append(line)
    return [(heading, "\n".join(body)) for heading, body in out]


def _level(heading: str) -> str:
    return heading.split(" ")[0]


def _heading_text(heading: str) -> str:
    return heading.lstrip("#").strip().lower()


def _jaccard(a: set[str], b: set[str]) -> float:
    shared = len(a & b)
    return shared / (len(a) + len(b) - shared) if a and b else 0.0


def _neutral_tokens(body: str) -> set[str]:
    tokens = {code.strip() for code in _CODE_SPAN.findall(body)}
    tokens.update(target.split("#")[0] for target in _LINK_TARGET.findall(body))
    for word in _TOKEN.findall(body):
        word = word.strip(".:-/")
        if len(word) >= 3 and _NEUTRAL.search(word):
            tokens.add(word)
    tokens.discard("")
    return tokens


def _features(section: tuple[str, str]) -> tuple[str, str, set[str], set[str]]:
    """Ebene, Ueberschriftentext, ihre Woerter und die sprachneutralen Zeichen des Abschnitts."""
    heading, body = section
    text = _heading_text(heading)
    return (_level(heading), text, {w for w in _HEADING_WORD.findall(text) if len(w) >= 2},
            _neutral_tokens(body))


def _similarity(de: tuple, en: tuple, matcher: difflib.SequenceMatcher) -> float:
    """Aehnlichkeit zweier Abschnitte gleicher Ebene; matcher traegt en[1] schon als zweite Folge."""
    words = _jaccard(de[2], en[2])
    # Unter drei Zeichen ist ein Treffer Zufall -- ein einzelnes `true` macht keine Verwandtschaft.
    body = _jaccard(de[3], en[3]) if len(de[3] | en[3]) >= 3 else 0.0
    # Buchstaben vergleichen nur, wenn kein Wort passt: fuer Kognate ("Architektur"/"Architecture").
    # Teilen die Ueberschriften Woerter, sagen die mehr -- und die API-Referenz mit ihren hunderten
    # aehnlichen Endpunkt-Ueberschriften wuerde sonst zur Rechenaufgabe. Erst ab 0.6, darunter
    # trifft er Zufallsbuchstaben ("Eins"/"One").
    letters = 0.0
    if not words:
        matcher.set_seq1(de[1])
        if matcher.real_quick_ratio() >= 0.6 and matcher.quick_ratio() >= 0.6:
            ratio = matcher.ratio()
            letters = ratio if ratio >= 0.6 else 0.0
    return max(words, body, letters)


def align_sections(de: list[tuple[str, str]], en: list[tuple[str, str]]) -> tuple[list[int], list[int]]:
    """Paart die Abschnitte beider Sprachen; liefert (DE ohne Partner, EN ohne Partner) als Indizes."""
    return align_scores(pair_scores(de, en))


def pair_scores(de: list[tuple[str, str]], en: list[tuple[str, str]]) -> list[list[float | None]]:
    """Wert jedes moeglichen Paares (DE-Index, EN-Index); None, wo die Ebenen verschieden sind."""
    features_de = [_features(section) for section in de]
    score: list[list[float | None]] = [[None] * len(en) for _ in de]
    for j, feature_en in enumerate(_features(section) for section in en):
        matcher = difflib.SequenceMatcher(None)
        matcher.set_seq2(feature_en[1])  # zweite Folge einmal je Ueberschrift, difflib cacht sie
        for i, feature_de in enumerate(features_de):
            if feature_de[0] == feature_en[0]:
                score[i][j] = PAIR_BASE + _similarity(feature_de, feature_en, matcher)
    return score


def align_scores(score: list[list[float | None]]) -> tuple[list[int], list[int]]:
    """Die reihenfolgetreue Paarung mit der hoechsten Summe; (Zeilen ohne Partner, Spalten ohne Partner)."""
    n = len(score)
    m = len(score[0]) if score else 0
    # best[i][j]: hoechste Summe fuer die Restfolgen de[i:] und en[j:].
    best = [[0.0] * (m + 1) for _ in range(n + 1)]
    for i in range(n - 1, -1, -1):
        for j in range(m - 1, -1, -1):
            value = max(best[i + 1][j], best[i][j + 1])
            if score[i][j] is not None:
                value = max(value, score[i][j] + best[i + 1][j + 1])
            best[i][j] = value
    # Rueckweg von vorn; bei Gleichstand gilt ein Paar vor einer Luecke, eine deutsche Luecke vor
    # einer englischen. Damit faellt die Vermutung ohne Signal auf den spaeteren Abschnitt.
    only_de: list[int] = []
    only_en: list[int] = []
    i = j = 0
    close = 1e-9
    while i < n and j < m:
        if score[i][j] is not None and abs(best[i][j] - (score[i][j] + best[i + 1][j + 1])) < close:
            i, j = i + 1, j + 1
        elif abs(best[i][j] - best[i + 1][j]) < close:
            only_de.append(i)
            i += 1
        else:
            only_en.append(j)
            j += 1
    only_de.extend(range(i, n))
    only_en.extend(range(j, m))
    return only_de, only_en


def _position(items: list[tuple[str, str]], k: int) -> str:
    """Wo der Abschnitt steht: Nummer der Ueberschrift und ihr Vorgaenger in derselben Datei."""
    after = f", nach '{items[k - 1][0]}'" if k > 0 else ""
    return f" (Ueberschrift {k + 1} von {len(items)}{after})"


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
