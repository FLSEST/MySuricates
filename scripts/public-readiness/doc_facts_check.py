#!/usr/bin/env python3
"""Vergleicht die in der Doku genannten Groessen mit den Zahlen aus dem Code.

Aufruf ueber scripts/public-readiness/doc-facts-check.sh — dort werden die
Zahlen deterministisch erhoben und hier als JSON in DOC_FACTS uebergeben.
argv sind die zu pruefenden Markdown-Dateien, relativ zum Repo-Wurzelpfad.

Ausgabe je Befund:
    DRIFT <datei>:<zeile> <groesse> sagt N, Code M | <Zeilenauszug>

Warum Regex und keine Freitext-Pruefung: die Zahlen driften bei jedem Release
(TODO 3df2df73). Ein Muster je Groesse ist absichtlich eng — lieber eine
Formulierung nicht erfassen als bei jedem Satz mit einer Zahl Alarm schlagen.
"""
import json
import os
import re
import sys

# ---------------------------------------------------------------------------
# Dateien, die bewusst alte Zahlen enthalten
#
# docs/analysis.md ist eine datierte Gap-Analyse ("Historische Momentaufnahme"):
# ihre Zahlen SIND der Befund von damals, sie duerfen nicht mitwandern.
# README.md steht nicht im Pruefumfang — es wird aus README.de.md erzeugt.
# ---------------------------------------------------------------------------
# docs/analysis.md, docs/plans/ und docs/specs/ sind seit 089473cd/72900609 aus dem
# getrackten Bestand genommen und ignoriert. In einer Arbeitskopie liegen sie oft
# trotzdem herum — sie sollen die Release-Frage nicht beeinflussen.
EXCLUDED_FILES = {"docs/analysis.md", "docs/en/analysis.md", "README.md"}
EXCLUDED_PREFIXES = ("docs/plans/", "docs/specs/")

# Zeilen mit diesem Marker werden uebersprungen (bewusst alte Zahl im Fliesstext).
IGNORE_MARKER = "doc-facts-check: ignore"

# ---------------------------------------------------------------------------
# Muster je Groesse
#
# Alle Muster verlangen die Zahl unmittelbar vor dem Substantiv (hoechstens ein
# erlaubtes Beiwort dazwischen). Damit faellt "4 audit templates" heraus — eine
# Teilmenge ist keine Gesamtzahl — und "OAuth2 providers" ebenfalls, weil \b
# zwischen "h" und "2" keine Wortgrenze ist.
#
# Schluessel = Name der Groesse (identisch mit der Ausgabe von --facts).
# ---------------------------------------------------------------------------
PATTERNS = {
    # Blocktypen: Quelle ist die Registry BlockTypesIndexSection.BLOCK_TYPES.
    "block-types": [
        r"\b(\d+)\s+[Bb]lock-?[Tt]ypen\b",
        r"\b(\d+)\s+block[- ]types?\b",
    ],
    # Controller: optionales Beiwort REST bzw. REST-.
    # (?:s|n) faengt englischen Plural und deutschen Dativ ("43 REST-Controllern").
    "controllers": [
        r"\b(\d+)\s+(?:REST[- ])?[Cc]ontroller(?:s|n)?\b",
    ],
    # Hoechste Migrationsnummer: nur die Spanne ab V1 ist eine Standangabe.
    # Tabellenzeilen sind ausgenommen, dort stehen echte Teilspannen (| V1-V9 |).
    "migration-max": [
        r"\bV1\s*[-‐-―]\s*V(\d+)\b",
    ],
    # Anzahl der Migrationsdateien.
    "migrations": [
        r"\b(\d+)\s+(?:Flyway-)?Migrationen\b",
        r"\b(\d+)\s+(?:Flyway )?migrations\b",
    ],
    # Built-in-Templates.
    "templates": [
        r"\b(\d+)\s+(?:Built-?in|Workflow|vorgefertigte|vorbereitete|vordefinierte)[- ]"
        r"(?:Workflow-)?(?:Templates?|Vorlagen)\b",
        r"\b(\d+)\s+(?:built-?in|workflow|pre-?built|pre-?defined)[- ]"
        r"(?:workflow )?templates?\b",
    ],
    # Built-in-Skills. Der optionale "+ <Wort>"-Einschub fängt die Glossar-Formel
    # „19 Built-in + benutzerdefinierte Skills" / „19 built-in + user-defined skills":
    # die Zahl gilt dort nur dem ersten Teil, der Zusatz steht bloß dazwischen.
    "skills": [
        r"\b(\d+)\s+(?:Built-?in|eingebaute|vordefinierte)(?:\s*\+\s*\S+)?[- ][Ss]kills?\b",
        r"\b(\d+)\s+(?:built-?in|predefined|pre-?defined)(?:\s*\+\s*\S+)?[- ]skills?\b",
    ],
    # Integrations-Provider. "KI-Provider" (Claude/Codex/Pi) ist etwas anderes
    # und faellt heraus, weil nur "Integrations-" als Beiwort erlaubt ist.
    "integration-providers": [
        r"\b(\d+)\s+(?:Integrations-)?Provider(?:s|n)?\b",
        r"\b(\d+)\s+(?:integration )?providers?\b",
    ],
}

# Groessen, deren Muster in Markdown-Tabellenzeilen nicht gelten.
TABLE_EXEMPT = {"migration-max"}

COMPILED = {name: [re.compile(p) for p in pats] for name, pats in PATTERNS.items()}


def is_excluded(rel):
    return rel in EXCLUDED_FILES or rel.startswith(EXCLUDED_PREFIXES)


_CURRENT_V = {}


def names_current_migration(line, expected):
    """Nennt die Zeile die hoechste Migration bereits selbst?

    Dann ist ein zusaetzliches "V1-Vn" darin eine Teilspanne und keine
    Standangabe. docs/onboarding/ARCHITECTURE.md schreibt genau so:
    "Die folgende Tabelle beschreibt das Grundschema (V1-V19). V20-V73
    ergaenzen ueberwiegend Spalten und Indizes." — V1-V19 ist dort richtig.
    """
    regex = _CURRENT_V.get(expected)
    if regex is None:
        regex = _CURRENT_V[expected] = re.compile(rf"\bV{expected}\b")
    return bool(regex.search(line))


def scan(root, rel, facts, findings):
    path = os.path.join(root, rel)
    try:
        with open(path, encoding="utf-8") as fh:
            lines = fh.readlines()
    except OSError:
        return
    for no, line in enumerate(lines, 1):
        # Codebloecke werden bewusst MIT geprueft: die Verzeichnisbaeume in
        # docs/architektur.md ("├── controller/   43 REST-Controller") stehen in
        # ```-Bloecken und sind genau die Stellen, die am haeufigsten driften.
        if IGNORE_MARKER in line:
            continue
        is_table = line.lstrip().startswith("|")
        for name, regexes in COMPILED.items():
            expected = facts.get(name)
            if expected is None:
                continue
            if is_table and name in TABLE_EXEMPT:
                continue
            if name == "migration-max" and names_current_migration(line, expected):
                continue
            for regex in regexes:
                for match in regex.finditer(line):
                    said = int(match.group(1))
                    if said != expected:
                        findings.append(
                            f"DRIFT {rel}:{no} {name} sagt {said}, Code {expected}"
                            f" | {line.strip()[:120]}")
    return


def main():
    root = os.environ.get("DOC_FACTS_ROOT") or "."
    try:
        facts = json.loads(os.environ["DOC_FACTS"])
    except (KeyError, ValueError) as exc:                          # noqa: BLE001
        print(f"doc-facts-check: DOC_FACTS nicht lesbar: {exc}", file=sys.stderr)
        return 2

    findings = []
    for rel in sys.argv[1:]:
        if is_excluded(rel):
            continue
        scan(root, rel, facts, findings)

    for line in findings:
        print(line)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
