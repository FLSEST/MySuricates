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

# Bindestrich und die Gedankenstriche, die in der Doku als Spannen-Trenner vorkommen.
DASH = r"[-‐-―]"

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
        r"\bV1\s*" + DASH + r"\s*V(\d+)\b",
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


# Kennzeichnungen, mit denen sich eine Spanne selbst als Auszug ausweist.
EXCERPT_MARKERS = re.compile(
    r"Grundschema|Basisschema|Teilspanne|Auszug|base schema|core schema|excerpt|subset",
    re.IGNORECASE)

_CONTINUATION = {}


def span_is_declared_excerpt(line, match, expected):
    """Weist sich GENAU DIESE Spanne als Auszug aus?

    Die Ausnahme deckt eine Formulierung ab, die es in der Doku wirklich gibt
    (docs/onboarding/ARCHITECTURE.md):

        "Die folgende Tabelle beschreibt das Grundschema (V1-V19). V20-V73
         ergaenzen ueberwiegend Spalten und Indizes."

    Zwei Belege muessen zusammenkommen:

    1. eine Auszugs-Kennzeichnung unmittelbar vor der Spanne (im selben Satzteil);
    2. dahinter — nur im Rest der Zeile, nicht davor — eine Fortsetzungsspanne,
       die luekenlos anschliesst (V<n+1>) und bis zur aktuellen Hoechstnummer
       laeuft.

    Eine blosse Standangabe im Nebensatz reicht nicht (frueher schaltete jedes
    "V73" irgendwo in der Zeile die ganze Zeile ab, sodass "Das Grundschema
    (V1-V50). Aktueller Stand ist V73." unbemerkt blieb), und eine Fortsetzung,
    die gar nicht anschliesst ("V1-V50. V20-V73"), belegt die Grenze der
    Teilspanne ebenso wenig. Geprueft wird jede Spanne fuer sich; andere Zahlen
    derselben Zeile laufen weiter durch die Pruefung.
    """
    segment = re.split(r"[.;:|]", line[:match.start()])[-1]
    if not EXCERPT_MARKERS.search(segment):
        return False
    said = int(match.group(1))
    if said >= expected:
        return False
    key = (said, expected)
    regex = _CONTINUATION.get(key)
    if regex is None:
        regex = _CONTINUATION[key] = re.compile(
            rf"\bV{said + 1}\s*" + DASH + rf"\s*V{expected}\b")
    return bool(regex.search(line[match.end():]))


# ---------------------------------------------------------------------------
# Migrationstabellen
#
# Die Tabellen in docs/datenmodell.md und docs/architektur.md listen je Migration
# eine Zeile "| V66 | ... |". Massgeblich ist die hoechste genannte Nummer: bleibt
# sie hinter dem Code zurueck, fehlen Zeilen. Ausgenommen sind Tabellen, die sich
# im Fliesstext als Auszug ausweisen, indem sie den aktuellen Stand daneben nennen
# (docs/onboarding/ARCHITECTURE.md listet V1-V19 und schreibt V73 in den Text).
#
# Zwei bewusste Grenzen:
# - Das Muster haengt an der schlichten Zelle "| V66 |" bzw. "| `V66` |". Eine
#   Tabelle, die die Nummer auszeichnet ("| **V66** |"), faellt heraus; kommt so
#   eine Schreibweise auf, gehoert sie hier ergaenzt.
# - Die Ausnahme wirkt dateiweit: nennt IRGENDEINE Fliesstext-Zeile der Datei die
#   Hoechstnummer, gelten ALLE Tabellen darin als ausgewiesener Auszug. Das ist
#   grob, aber vom Auftrag so gewollt — die eine Formulierung in
#   docs/onboarding/ARCHITECTURE.md ist der ganze Anwendungsfall.
# ---------------------------------------------------------------------------
# Eine Zeile darf eine Nummer ODER eine Spanne tragen: docs/architektur.md fasst zwei
# Migrationen zu "| V28–V29 | Wishes + Wish-Reference-Workflow-IDs |" zusammen. Ohne die
# Spanne haelt die Vollstaendigkeitspruefung das fuer ein Loch (sie tat es beim ersten Lauf).
TABLE_MIGRATION_ROW = re.compile(rf"^\|\s*`?V(\d+)`?(?:\s*{DASH}\s*`?V(\d+)`?)?\s*\|")

# Kopftabelle von README.de.md: "rund 1 420 Commits" — der Text sagt "rund",
# deshalb mit Toleranz. Tausendertrenner koennen Leer-, Schmal- oder Punktzeichen sein.
COMMITS_CLAIM = re.compile(r"\b([0-9][0-9.   ' ]*)\s*Commits\b")
COMMITS_TOLERANCE = 0.05

# Testzahlen altern schneller als alles andere. Sie duerfen stehen bleiben, solange
# sie datiert sind ("Stand 2026-09-18: 715 Testklassen") — eine undatierte Testzahl
# behauptet Aktualitaet, die niemand geprueft hat.
TEST_CLAIM = re.compile(
    r"\b\d[\d.    ]*\s*(?:Testklassen|Testdateien|Vitest-Dateien|Cypress-Specs"
    r"|test classes|test files|Vitest files|Cypress specs)\b"
    r"|^\|\s*Tests\s*\|", re.IGNORECASE)
ISO_DATE = re.compile(r"\b\d{4}-\d{2}-\d{2}\b")

README_SOURCE = "README.de.md"

# ---------------------------------------------------------------------------
# Technologietabelle: "| **React** | 19.2 | UI-Framework |"
#
# Gilt fuer JEDE gepruefte Datei, nicht nur fuers Onboarding — heute stehen
# solche Tabellen nur in docs/onboarding/README.md (DE/EN), eine kuenftige
# anderswo soll aber genauso mitlaufen. Die Doku kuerzt, deshalb gilt die
# genannte Version als richtig, wenn sie ein komponentenweises Praefix der
# echten ist (19.2 zu 19.2.4 ja, 19.21 zu 19.2.4 nein).
# ---------------------------------------------------------------------------
VERSION_ROW = re.compile(r"^\|\s*\*\*([^*|]+)\*\*\s*\|([^|]*)\|")
VERSION_NUMBER = re.compile(r"\d+(?:\.\d+)+")

# ---------------------------------------------------------------------------
# Verbotene Muster, fehlende Dateien, undatierte Testzahlen: gelten ueberall
#
# Die drei Regeln kamen am 2026-09-18 mit der Handpruefung des Onboarding-Pakets
# (TODO 8c75f1dd) und galten zunaechst nur dort — nicht aus einem inhaltlichen
# Grund, sondern weil je eine Fundstelle ausserhalb erst umformuliert werden
# musste. Seit TODO c9c9f3eb ist das erledigt und die Regeln laufen ueber alle
# geprueften Dateien:
#
# - Kommando-Muster: docs/entwicklung.md nannte `mvn spring-boot:run` als
#   Gegen-Hinweis im Fliesstext; der Hinweis steht jetzt als Blockquote und ist
#   damit von der Regel ausgenommen (check_forbidden_patterns laesst "> " durch).
# - Dateinamen-Muster: die Nennung von tailwind.config.ts in docs/architektur.md
#   ist entfallen. Die verbliebenen Tailwind-Stellen sprechen von
#   `tailwind.config.js`/`.*` und sagen ausdruecklich, dass es die Datei NICHT
#   gibt — ein anderer Name, den die Regel nicht trifft.
# - Testzahl-Datierung: die undatierten Testzahlen in docs/ wurden datiert.
#
# Wer eine dieser Muster-Arten legitim braucht, schreibt sie als Blockquote
# (Kommandos) oder setzt den IGNORE_MARKER in die Zeile.
# ---------------------------------------------------------------------------

# Verbotene Muster. Blockquote-Zeilen sind ausgenommen: dort steht das Muster
# als Warnung ("Nicht `mvn spring-boot:run` verwenden"), nicht als Anleitung.
FORBIDDEN_PATTERNS = [
    (re.compile(r"mvn\s+spring-boot:run"),
     "mvn spring-boot:run — der RuntimeClasspathGuard bricht den Start ab"),
    (re.compile(r"React Router v[1-6]\b"),
     "veraltete React-Router-Version (package.json fuehrt v7)"),
    # Dauerhaft gueltig: Modell-IDs mit Versions- oder Datumsstand veralten von
    # selbst, in der Doku gelten die Aliase opus/sonnet/haiku/fable.
    #
    # Mindestens ZWEI Zahlengruppen verlangt: `claude-opus-4-6` und
    # `claude-haiku-4-5-20251001` veralten, `claude-opus-5` nicht — eine
    # einsegmentige ID ist laut CLAUDE.md eine erlaubte konkrete Angabe, und
    # docs/architektur.md nennt sie genau so als Beispiel.
    (re.compile(r"claude-(?:opus|sonnet|haiku|fable)-\d+-\d"),
     "datierte Modell-ID — in der Doku gelten die Aliase opus/sonnet/haiku/fable"),
]


def digits(text):
    """"1 420" / "1.420" -> 1420."""
    return int(re.sub(r"\D", "", text))


def check_migration_table(rel, lines, expected, numbers, findings):
    if expected is None:
        return
    current = re.compile(rf"\bV{expected}\b")
    best = best_no = best_line = None
    seen = {}
    names_current_outside = False
    for no, line in enumerate(lines, 1):
        if IGNORE_MARKER in line:
            continue
        match = TABLE_MIGRATION_ROW.match(line.lstrip())
        if match:
            first = int(match.group(1))
            last = int(match.group(2)) if match.group(2) else first
            for said in range(first, max(first, last) + 1):
                seen.setdefault(said, no)
            said = max(first, last)
            if best is None or said > best:
                best, best_no, best_line = said, no, line
        elif current.search(line):
            names_current_outside = True
    if best == expected:
        check_migration_table_complete(rel, seen, best, best_no, numbers, findings)
    if best is None or best == expected or names_current_outside:
        return
    findings.append(f"DRIFT {rel}:{best_no} migration-table sagt {best}, Code {expected}"
                    f" | {best_line.strip()[:120]}")


def check_migration_table_complete(rel, seen, best, best_no, numbers, findings):
    """Keine Luecke zwischen der kleinsten und der groessten gelisteten Nummer.

    Die Pruefung auf die HOECHSTE Nummer allein hat eine blinde Stelle, und sie ist beim
    Nachtragen von V75 (TODO ffa6d846) aufgefallen: Ein fehlerhafter Einschub hat die
    V74-Zeile zerschnitten, die Tabelle sprang von V73 auf V75 — und das Gate blieb gruen,
    weil die Hoechstnummer stimmte. Eine Tabelle mit Loch ist als Nachschlagewerk aber genau
    dort falsch, wo jemand nachsieht.

    Geprueft wird nur, wenn die Tabelle bis zur aktuellen Hoechstnummer reicht — dann
    behauptet sie Vollstaendigkeit, und ein Loch ist ein Befund. Bleibt sie darunter, meldet
    das schon die Standpruefung, und ein ausgewiesener Auszug (docs/onboarding) soll nicht
    zusaetzlich wegen fehlender Zeilen anschlagen. Die Spanne beginnt bei der kleinsten
    gelisteten Nummer: Eine Tabelle, die bewusst erst bei V20 anfaengt, ist kein Befund.

    Verglichen wird gegen die WIRKLICH vorhandenen Migrationsdateien (migration-numbers),
    nicht gegen eine durchgezaehlte Reihe. Sonst waere eine nie existierende Nummer ein
    Fehlalarm — Migrationsnummern koennen uebersprungen werden.
    """
    if best is None or not numbers:
        return
    missing = [n for n in numbers if min(seen) <= n <= best and n not in seen]
    if not missing:
        return
    shown = ", ".join(f"V{n}" for n in missing[:8]) + ("…" if len(missing) > 8 else "")
    findings.append(f"DRIFT {rel}:{best_no} migration-table-luecke: {len(missing)} Zeile(n)"
                    f" fehlen zwischen V{min(seen)} und V{best} ({shown})")


def check_readme_head(rel, no, line, facts, findings):
    """Kopftabelle von README.de.md: Commit-Zahl und Datierung der Testzahlen."""
    expected = facts.get("commits")
    if expected is not None:
        for match in COMMITS_CLAIM.finditer(line):
            said = digits(match.group(1))
            if abs(said - expected) > max(1, round(expected * COMMITS_TOLERANCE)):
                findings.append(f"DRIFT {rel}:{no} commits sagt {said}, Code {expected}"
                                f" | {line.strip()[:120]}")


def check_version_row(rel, no, line, versions, findings):
    """Technologietabelle gegen pom.xml / package.json."""
    if not versions:
        return
    match = VERSION_ROW.match(line.strip())
    if not match:
        return
    expected = versions.get(match.group(1).strip())
    if not expected:
        return
    said = VERSION_NUMBER.search(match.group(2))
    if not said:
        return
    said = said.group(0)
    parts = said.split(".")
    if expected.split(".")[:len(parts)] != parts:
        findings.append(f"DRIFT {rel}:{no} version({match.group(1).strip()}) sagt {said},"
                        f" Code {expected} | {line.strip()[:120]}")


def check_forbidden_patterns(rel, no, line, findings):
    if line.lstrip().startswith(">"):
        return
    for regex, reason in FORBIDDEN_PATTERNS:
        if regex.search(line):
            findings.append(f"DRIFT {rel}:{no} verbotenes-muster: {reason}"
                            f" | {line.strip()[:120]}")


def check_absent_files(rel, no, line, absent, findings):
    for name in absent or ():
        if name in line:
            findings.append(f"DRIFT {rel}:{no} fehlende-datei: '{name}' gibt es im"
                            f" Repository nicht | {line.strip()[:120]}")


def sentence_continues(line):
    """Laeuft der Satz dieser Zeile in die naechste weiter?

    Zwei Belege, beide aus der echten Doku:
    - eine offene Klammer (die Quellen- und Standangabe steht darin:
      "73 test classes in the repo (as of\\n2026-09-18, `grep ...`)");
    - oder die Zeile endet ohne Satzzeichen, also mitten im Satz.

    Ohne diese Pruefung wuerde eine beliebige datierte Nachbarzeile eine
    undatierte Testzahl stumm stellen — genau das hat der Fixture-Fall
    "Unter src/test/java liegen 715 Testklassen." aufgedeckt: Der Satz ist mit
    dem Punkt zu Ende, das Datum der Folgezeile gehoert zu einer anderen Aussage.
    """
    stripped = line.rstrip()
    if stripped.count("(") > stripped.count(")"):
        return True
    return bool(stripped) and stripped[-1] not in ".!?|"


def check_test_claim_dated(rel, no, line, next_line, findings):
    """Testzahl braucht ein Datum — auch eines, das hinter dem Umbruch steht.

    Die Doku bricht Zeilen bei ~100 Zeichen um; die Datierung landet dabei
    regelmaessig in der Folgezeile. Sie ist dann vorhanden, nur nicht in
    derselben Zeile — deshalb zaehlt sie mit, aber nur wenn der Satz dort
    tatsaechlich weiterlaeuft (siehe sentence_continues).
    """
    dated_next = sentence_continues(line) and ISO_DATE.search(next_line or "")
    if TEST_CLAIM.search(line.strip()) and not ISO_DATE.search(line) and not dated_next:
        findings.append(f"DRIFT {rel}:{no} testzahl-ohne-datum: Testzahlen nur mit Datum"
                        f" (z. B. 'Stand 2026-09-18') | {line.strip()[:120]}")


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
            for regex in regexes:
                for match in regex.finditer(line):
                    said = int(match.group(1))
                    if name == "migration-max" and span_is_declared_excerpt(
                            line, match, expected):
                        continue
                    if said != expected:
                        findings.append(
                            f"DRIFT {rel}:{no} {name} sagt {said}, Code {expected}"
                            f" | {line.strip()[:120]}")
        check_version_row(rel, no, line, facts.get("versions"), findings)
        if rel == README_SOURCE:
            check_readme_head(rel, no, line, facts, findings)
        check_forbidden_patterns(rel, no, line, findings)
        check_absent_files(rel, no, line, facts.get("absent-files"), findings)
        check_test_claim_dated(rel, no, line, lines[no] if no < len(lines) else "",
                              findings)
    check_migration_table(rel, lines, facts.get("migration-max"),
                          facts.get("migration-numbers"), findings)
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
