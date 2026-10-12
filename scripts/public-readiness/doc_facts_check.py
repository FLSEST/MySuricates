#!/usr/bin/env python3
"""Compares the figures named in the docs with the numbers from the code.

Called via scripts/public-readiness/doc-facts-check.sh — there the
numbers are collected deterministically and passed here as JSON in DOC_FACTS.
argv are the Markdown files to check, relative to the repo root path.

Output per finding:
    DRIFT <datei>:<zeile> <groesse> sagt N, Code M | <Zeilenauszug>

Why regex and no free-text check: the numbers drift with every release
(TODO 3df2df73). One pattern per quantity is deliberately narrow — better to miss a
phrasing than to raise an alarm on every sentence containing a number.
"""
import json
import os
import re
import sys

# ---------------------------------------------------------------------------
# Files that deliberately contain old numbers
#
# docs/analysis.md is a dated gap analysis ("Historische Momentaufnahme"):
# its numbers ARE the finding of back then, they must not move along.
# README.md is not in the check scope — it is generated from README.de.md.
# ---------------------------------------------------------------------------
# docs/analysis.md, docs/plans/ and docs/specs/ have been taken out of the tracked set since 089473cd/72900609
# and ignored. In a working copy they often lie around anyway —
# they must not influence the release question.
EXCLUDED_FILES = {"docs/analysis.md", "docs/en/analysis.md", "README.md"}
EXCLUDED_PREFIXES = ("docs/plans/", "docs/specs/")

# Lines with this marker are skipped (deliberately old number in running text).
IGNORE_MARKER = "doc-facts-check: ignore"

# Hyphen and the dashes that appear in the docs as range separators.
DASH = r"[-‐-―]"

# ---------------------------------------------------------------------------
# Pattern per quantity
#
# All patterns require the number immediately before the noun (at most one
# allowed adjective in between). That excludes "4 audit templates" — a
# subset is not a total — and "OAuth2 providers" as well, because \b
# between "h" and "2" is not a word boundary.
#
# Key = name of the quantity (identical to the output of --facts).
# ---------------------------------------------------------------------------
PATTERNS = {
    # Block types: source is the registry BlockTypesIndexSection.BLOCK_TYPES.
    # Deliberately NOT captured: "(N Typen)" after a block word. docs/assistant-system.md
    # used it there for the number of the assistant's block specifications — a subset of the
    # block types that, measured against the total, would yield a false alarm (TODO e36ca773).
    "block-types": [
        r"\b(\d+)\s+[Bb]lock-?[Tt]ypen\b",
        r"\b(\d+)\s+block[- ]types?\b",
    ],
    # Controllers: optional adjective REST or REST-.
    # (?:s|n) catches the English plural and the German dative ("43 REST-Controllern").
    "controllers": [
        r"\b(\d+)\s+(?:REST[- ])?[Cc]ontroller(?:s|n)?\b",
    ],
    # Highest migration number: only the range from V1 is a state statement.
    # Table rows are exempt, they hold real sub-ranges (| V1-V9 |).
    # "hoechste (vorhandene) Migration: V73" and "V73 als hoechste Migration" state the
    # status just the same — that is how it stood undated in docs/onboarding/FAQ.md (TODO e36ca773).
    "migration-max": [
        r"\bV1\s*" + DASH + r"\s*V(\d+)\b",
        r"\b(?:h(?:ö|oe)chste|highest|latest)\s+(?:vorhandene\s+|existing\s+)?[Mm]igration"
        r"(?:snummer)?\s*:?\s*`?V(\d+)\b",
        r"\bV(\d+)`?\s+(?:als|as the)\s+(?:h(?:ö|oe)chste|highest)\s+[Mm]igration",
    ],
    # Number of migration files, also as "(N)" after the source path.
    "migrations": [
        r"\b(\d+)\s+(?:Flyway-)?Migrationen\b",
        r"\b(\d+)\s+(?:Flyway )?migrations\b",
        r"db/migration/?(?:\*\.sql|V\*\.sql)?`?\s*\((\d+)\)",
    ],
    # Built-in-Templates.
    "templates": [
        r"\b(\d+)\s+(?:Built-?in|Workflow|vorgefertigte|vorbereitete|vordefinierte)[- ]"
        r"(?:Workflow-)?(?:Templates?|Vorlagen)\b",
        r"\b(\d+)\s+(?:built-?in|workflow|pre-?built|pre-?defined)[- ]"
        r"(?:workflow )?templates?\b",
        # "unter *Templates* liegen 67 fertige Workflows" (docs/GETTING-STARTED.md): the number
        # belongs to the templates because the sentence names them immediately before. Without this
        # link "N fertige Workflows" would also be pool entries — no source here counts those.
        r"\bTemplates\b\*?[^.\n]{0,40}?\b(\d+)\s+(?:fertige|ready-?made)\s+[Ww]orkflows\b",
        # "(N)" after the source path, e.g. in docs/workflow-pool-kuratierung.md.
        r"templates/builtin/?(?:\*\.json)?`?\s*\((\d+)\)",
    ],
    # Built-in skills. The optional "+ <word>" insertion catches the glossary formula
    # „19 Built-in + benutzerdefinierte Skills" / „19 built-in + user-defined skills":
    # the number there applies only to the first part, the addition just sits in between.
    "skills": [
        r"\b(\d+)\s+(?:Built-?in|eingebaute|vordefinierte)(?:\s*\+\s*\S+)?[- ][Ss]kills?\b",
        r"\b(\d+)\s+(?:built-?in|predefined|pre-?defined)(?:\s*\+\s*\S+)?[- ]skills?\b",
        r"skills/builtin/?(?:\*\.json)?`?\s*\((\d+)\)",
    ],
    # Integration providers. "KI-Provider" (Claude/Codex/Pi) is something different
    # and drops out because only "Integrations-" is allowed as an adjective.
    "integration-providers": [
        r"\b(\d+)\s+(?:Integrations-)?Provider(?:s|n)?\b",
        r"\b(\d+)\s+(?:integration )?providers?\b",
    ],
}

# Quantities whose patterns do not apply in Markdown table rows.
TABLE_EXEMPT = {"migration-max"}

COMPILED = {name: [re.compile(p) for p in pats] for name, pats in PATTERNS.items()}


def is_excluded(rel):
    return rel in EXCLUDED_FILES or rel.startswith(EXCLUDED_PREFIXES)


# Markers with which a range identifies itself as an excerpt.
EXCERPT_MARKERS = re.compile(
    r"Grundschema|Basisschema|Teilspanne|Auszug|base schema|core schema|excerpt|subset",
    re.IGNORECASE)

_CONTINUATION = {}


def span_is_declared_excerpt(line, match, expected):
    """Does THIS VERY range identify itself as an excerpt?

    The exception covers a phrasing that really exists in the docs
    (docs/onboarding/ARCHITECTURE.md):

        "Die folgende Tabelle beschreibt das Grundschema (V1-V19). V20-V73
         ergaenzen ueberwiegend Spalten und Indizes."

    Two pieces of evidence must come together:

    1. an excerpt marker immediately before the range (in the same clause);
    2. after it — only in the rest of the line, not before — a continuation range
       that connects without a gap (V<n+1>) and runs up to the current
       highest number.

    A mere state statement in a subordinate clause is not enough (formerly any
    "V73" anywhere in the line switched off the whole line, so that "Das Grundschema
    (V1-V50). Aktueller Stand ist V73." went unnoticed), and a continuation
    that does not connect at all ("V1-V50. V20-V73") proves the boundary of the
    sub-range just as little. Each range is checked on its own; other numbers
    of the same line continue through the check.
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
# Migration tables
#
# The tables in docs/datenmodell.md and docs/architektur.md list one row
# "| V66 | ... |" per migration. The highest named number is decisive: if
# it lags behind the code, rows are missing. Exempt are tables that identify
# themselves in running text as an excerpt by naming the current state next to them
# (docs/onboarding/ARCHITECTURE.md lists V1-V19 and writes V73 in the text).
#
# Two deliberate limits:
# - The pattern hangs on the plain cell "| V66 |" or "| `V66` |". A
#   table that marks up the number ("| **V66** |") drops out; if such
#   a notation appears, it belongs added here.
# - The exception works file-wide: if ANY running-text line of the file names the
#   highest number, ALL tables in it count as an identified excerpt. That is
#   coarse, but intended by the task — the one phrasing in
#   docs/onboarding/ARCHITECTURE.md is the whole use case.
# ---------------------------------------------------------------------------
# A row may carry a number OR a range: docs/architektur.md merges two
# migrations into "| V28–V29 | Wishes + Wish-Reference-Workflow-IDs |". Without the
# range the completeness check takes that for a hole (it did so on the first run).
TABLE_MIGRATION_ROW = re.compile(rf"^\|\s*`?V(\d+)`?(?:\s*{DASH}\s*`?V(\d+)`?)?\s*\|")

# Header table of README.de.md: "rund 1 420 Commits" — the text says "rund",
# hence with tolerance. Thousands separators can be space, narrow space or dot characters.
COMMITS_CLAIM = re.compile(r"\b([0-9][0-9.   ' ]*)\s*Commits\b")
COMMITS_TOLERANCE = 0.05

# Reference date of a section: "Stand 2026-09-22 (Commit `d23a09ed`, …)". A dated number is
# true on the reference date, not at HEAD — otherwise the gate gets redder with every commit although the text
# is right (TODO 65c6dedc). Applies to the whole section (heading to heading), because
# the statement is sometimes before the table and sometimes after the sentence.
STICHTAG = re.compile(r"\bStand\b[^\n`]*?Commit\s+`([0-9a-f]{7,40})`")
HEADING = re.compile(r"^#{1,6}\s")

# Test counts age faster than anything else. They may stay as long as
# they are dated ("Stand 2026-09-18: 715 Testklassen") — an undated test count
# claims currency that nobody has verified.
TEST_CLAIM = re.compile(
    r"\b\d[\d.    ]*\s*(?:Testklassen|Testdateien|Vitest-Dateien|Cypress-Specs"
    r"|test classes|test files|Vitest files|Cypress specs)\b"
    r"|^\|\s*Tests\s*\|", re.IGNORECASE)
ISO_DATE = re.compile(r"\b\d{4}-\d{2}-\d{2}\b")

README_SOURCE = "README.de.md"

# ---------------------------------------------------------------------------
# Technology table: "| **React** | 19.2 | UI-Framework |"
#
# Applies to EVERY checked file — the only such table was in
# docs/onboarding/README.md (dropped in the docs rework, TODO 1ab3ad21), but a
# future one should be covered just the same. The docs abbreviate, so the
# stated version counts as correct if it is a component-wise prefix of the
# real one (19.2 for 19.2.4 yes, 19.21 for 19.2.4 no).
# ---------------------------------------------------------------------------
VERSION_ROW = re.compile(r"^\|\s*\*\*([^*|]+)\*\*\s*\|([^|]*)\|")
VERSION_NUMBER = re.compile(r"\d+(?:\.\d+)+")

# ---------------------------------------------------------------------------
# Forbidden patterns, missing files, undated test counts: apply everywhere
#
# The three rules came on 2026-09-18 with the manual check of the onboarding package
# (TODO 8c75f1dd) and at first applied only there — not for a reason of
# content, but because one occurrence each outside had to be reworded first.
# Since TODO c9c9f3eb that is done and the rules run over all
# checked files:
#
# - Command pattern: docs/entwicklung.md named `mvn spring-boot:run` as a
#   counter-hint in running text; the hint is now a blockquote and is
#   thereby exempt from the rule (check_forbidden_patterns lets "> " through).
# - File name pattern: the mention of tailwind.config.ts in docs/architektur.md
#   is gone. The remaining Tailwind places speak of
#   `tailwind.config.js`/`.*` and explicitly say that the file does NOT
#   exist — a different name that the rule does not hit.
# - Test count dating: the undated test counts in docs/ were dated.
#
# Whoever legitimately needs one of these pattern kinds writes it as a blockquote
# (commands) or puts the IGNORE_MARKER in the line.
# ---------------------------------------------------------------------------

# Forbidden patterns. Blockquote lines are exempt: there the pattern stands
# as a warning ("Nicht `mvn spring-boot:run` verwenden"), not as instructions.
FORBIDDEN_PATTERNS = [
    (re.compile(r"mvn\s+spring-boot:run"),
     "mvn spring-boot:run — der RuntimeClasspathGuard bricht den Start ab"),
    (re.compile(r"React Router v[1-6]\b"),
     "veraltete React-Router-Version (package.json fuehrt v7)"),
    # Permanently valid: model IDs with a version or date stamp go stale
    # by themselves, in the docs the aliases opus/sonnet/haiku/fable apply.
    #
    # At least TWO number groups required: `claude-opus-4-6` and
    # `claude-haiku-4-5-20251001` go stale, `claude-opus-5` does not — a
    # single-segment ID is an allowed concrete value according to CLAUDE.md, and
    # docs/architektur.md names it exactly so as an example.
    (re.compile(r"claude-(?:opus|sonnet|haiku|fable)-\d+-\d"),
     "datierte Modell-ID — in der Doku gelten die Aliase opus/sonnet/haiku/fable"),
    # The product is called MySuricates. "AutoPilot" is only the name of the project under which
    # MySuricates keeps its own TODOs — until e3101cd6 it still appeared as "AutoPilot — …" in
    # the title of all diagram pages. Only the heading with a dash is checked; the project name
    # in running text stays allowed.
    (re.compile(r"^#{1,6}\s+AutoPilot\s+[—–-]\s"),
     "alter Produktname im Seitentitel — das Produkt heisst MySuricates, AutoPilot nur das Projekt"),
]

# ---------------------------------------------------------------------------
# Fixed numbers about the shipped definitions (TODO 4846ccca)
#
# docs/architektur.md wrote: "In den mitgelieferten Definitionen kommt diese Form
# achtmal vor (sieben Pool-Workflows, ein Built-in-Template)". The number was already wrong
# when written, was recounted and corrected (sechsmal, kein Built-in) — and
# would have been wrong again with the next pool entry. The DE and the
# EN version even contradicted each other at times.
#
# Nobody recounts in the docs; the gates do it on every run
# (PlatformPromptQuotingGateTest counts its candidates itself and fails
# if it finds none; TemplateHygieneGateTest checks the quoting form). The prose
# therefore should hold the pointer to the checking gate, not the number.
#
# Narrowly drawn like all patterns here: it needs BOTH — a number that counts an occurrence
# ("achtmal", "eight times", "sieben Pool-Workflows", "ein Built-in"), AND in the
# vicinity of one line (the docs wrap at ~100 characters) talk of the
# shipped definitions. Totals like "66 Built-in-Templates" do not hit the
# rule: their sentence does not speak of the shipped definitions, and they have
# their own check measured against the code anyway, with `templates`.
#
# "einmal"/"once" deliberately does not count — in German that is an ordinary
# word ("zeigt es genau einmal") and not a stock statement.
# ---------------------------------------------------------------------------
_NUMBER_WORDS = (r"zwei|drei|vier|fuenf|fünf|sechs|sieben|acht|neun|zehn"
                 r"|two|three|four|five|six|seven|eight|nine|ten")
_COUNTS = rf"\d+|ein|eine|einem|einen|one|{_NUMBER_WORDS}"

# Vicinity in which a number would be a stock statement about the shipped definitions.
SHIPPED_DEFINITIONS = re.compile(
    r"(?:aus|mit)gelieferten\s+(?:Definitionen|Templates)"
    r"|(?:shipped|bundled)\s+(?:definitions|templates)", re.IGNORECASE)

# The number itself: as an occurrence adverb or immediately before the counted thing.
DEFINITION_COUNT = re.compile(
    rf"\b(?:\d+|{_NUMBER_WORDS})\s*mal\b"
    rf"|\b(?:\d+|{_NUMBER_WORDS})\s+times\b"
    rf"|\b(?:{_COUNTS})\s+(?:Pool-(?:Workflows?|Definitionen)|pool\s+workflows?"
    rf"|built-?ins?|built-?in[- ]templates?)\b", re.IGNORECASE)


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
    """No gap between the smallest and the largest listed number.

    Checking the HIGHEST number alone has a blind spot, and it was noticed when
    adding V75 (TODO ffa6d846): a faulty insertion cut the
    V74 row apart, the table jumped from V73 to V75 — and the gate stayed green
    because the highest number was right. A table with a hole is wrong as a reference
    exactly where someone looks things up.

    Checked only if the table reaches up to the current highest number — then
    it claims completeness, and a hole is a finding. If it stays below, the
    state check already reports that, and an identified excerpt (docs/onboarding) should not
    additionally trigger because of missing rows. The range starts at the smallest
    listed number: a table that deliberately starts only at V20 is no finding.

    Compared against the migration files that ACTUALLY exist (migration-numbers),
    not against a counted-through sequence. Otherwise a never-existing number would be a
    false alarm — migration numbers can be skipped.
    """
    if best is None or not numbers:
        return
    missing = [n for n in numbers if min(seen) <= n <= best and n not in seen]
    if not missing:
        return
    shown = ", ".join(f"V{n}" for n in missing[:8]) + ("…" if len(missing) > 8 else "")
    findings.append(f"DRIFT {rel}:{best_no} migration-table-luecke: {len(missing)} Zeile(n)"
                    f" fehlen zwischen V{min(seen)} und V{best} ({shown})")


def section_stichtage(lines):
    """Per line the reference-date commit of its section (or None); code blocks do not separate."""
    result, start, fenced = [None] * len(lines), 0, False

    def assign(first, end):
        sha = next((m.group(1) for m in map(STICHTAG.search, lines[first:end]) if m), None)
        for i in range(first, end):
            result[i] = sha

    for i, line in enumerate(lines):
        if line.lstrip().startswith("```"):
            fenced = not fenced
        elif not fenced and HEADING.match(line) and i > start:
            assign(start, i)
            start = i
    assign(start, len(lines))
    return result


def check_readme_head(rel, no, line, facts, findings, stichtag=None):
    """Header table of README.de.md: commit count (at the reference date of the section, else at HEAD)."""
    expected, basis = facts.get("commits"), "Code"
    if stichtag is not None and COMMITS_CLAIM.search(line):
        known = facts.get("commits-at", {})
        if known.get(stichtag) is None:
            findings.append(f"DRIFT {rel}:{no} stichtag-commit {stichtag} unbekannt oder nicht in der"
                            f" Historie von HEAD – die Commit-Zahl ist nicht pruefbar | {line.strip()[:120]}")
            return
        expected, basis = known[stichtag], f"Stand {stichtag}"
    if expected is not None:
        for match in COMMITS_CLAIM.finditer(line):
            said = digits(match.group(1))
            if abs(said - expected) > max(1, round(expected * COMMITS_TOLERANCE)):
                findings.append(f"DRIFT {rel}:{no} commits sagt {said}, {basis} {expected}"
                                f" | {line.strip()[:120]}")


def check_version_row(rel, no, line, versions, findings):
    """Technology table against pom.xml / package.json."""
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
    if expected == "@managed":
        findings.append(f"DRIFT {rel}:{no} version({match.group(1).strip()}) sagt {said}, die Version"
                        f" verwaltet der Spring-Boot-Parent – Zahl weglassen | {line.strip()[:120]}")
        return
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


def check_definition_count(rel, no, lines, findings):
    """Fixed occurrence count about the shipped definitions (see above).

    Reported at the line where the number stands; the neighbouring lines only supply
    the evidence that the shipment is being discussed — and only if the sentence
    really continues there (sentence_continues, the same check as for the
    test count dating). Without this condition any neighbouring line containing
    the words "ausgelieferte Definitionen" would colour the next number —
    for example the total of built-in templates in the sentence below.
    """
    line = lines[no - 1]
    match = DEFINITION_COUNT.search(line)
    if not match:
        return
    # Join in reading order: "mitgelieferten" at the line end and
    # "Definitionen" at the start of the next line is exactly the case the
    # window check is meant to catch — joined the other way round it would never find it.
    previous = lines[no - 2] if no >= 2 else ""
    window = [previous] if sentence_continues(previous) else []
    window.append(line)
    if sentence_continues(line):
        window.append(lines[no] if no < len(lines) else "")
    if not SHIPPED_DEFINITIONS.search(" ".join(window)):
        return
    findings.append(f"DRIFT {rel}:{no} zahl-ueber-ausgelieferte-definitionen:"
                    f" '{match.group(0).strip()}' altert mit jedem Pool-Eintrag — statt der Zahl"
                    f" das pruefende Gate nennen (PlatformPromptQuotingGateTest,"
                    f" TemplateHygieneGateTest) | {line.strip()[:120]}")


# ---------------------------------------------------------------------------
# Enumeration of the block types: "type": "simple | shell | ... | integration"
#
# docs/datenmodell.md showed the BlockDefinition with 14 types when there were 20
# (TODO e36ca773). No number a pattern could catch — the gap sits in
# the list itself. A "type" list counts as an enumeration of the block types only if it
# names more than half of all block types — then it claims to be the list.
# Other type fields ("type": "stop | continue") and short examples stay out.
# ---------------------------------------------------------------------------
TYPE_ENUM = re.compile(r'"type"\s*:\s*"([a-z][a-z-]*(?:\s*\|\s*[a-z][a-z-]*)+)"')


def check_block_type_enum(rel, no, line, names, findings):
    if not names:
        return
    for match in TYPE_ENUM.finditer(line):
        items = [item.strip() for item in match.group(1).split("|")]
        known = [item for item in items if item in names]
        if len(known) * 2 <= len(names):
            continue
        missing = [name for name in names if name not in items]
        unknown = [item for item in items if item not in names]
        if not missing and not unknown:
            continue
        detail = []
        if missing:
            detail.append("es fehlen " + ", ".join(missing))
        if unknown:
            detail.append("unbekannt " + ", ".join(unknown))
        findings.append(f"DRIFT {rel}:{no} block-type-enum nennt {len(known)} von {len(names)}"
                        f" Blocktypen ({'; '.join(detail)}) | {line.strip()[:120]}")


# ---------------------------------------------------------------------------
# Controller enumeration (TODO 65eab274)
#
# docs/entwicklung.md (DE/EN) lists every controller by name under the controller count
# ("**Controller:** ActivityLog, Assistant, …"). check_sizes checks the number, the list
# drifted anyway twice (51 named, 47 listed). Both directions are compared:
# every *Controller.java under src/main/java must be named, every named
# name must exist as a class. Names without the suffix "Controller"; if it is there anyway
# (or in backticks), it is stripped off.
# ---------------------------------------------------------------------------
CONTROLLER_ENUM = re.compile(r"^\s*\*\*Controllers?:\*\*\s*(.+?)\s*$")


def check_controller_enum(rel, no, line, names, findings):
    if not names:
        return
    match = CONTROLLER_ENUM.match(line)
    if not match:
        return
    items = []
    for raw in match.group(1).split(","):
        item = raw.strip().strip("`").rstrip(".").strip("`").strip()
        if item.endswith("Controller") and item != "Controller":
            item = item[: -len("Controller")]
        if item:
            items.append(item)
    known = set(names)
    missing = sorted(known - set(items))
    unknown = sorted(set(items) - known)
    if not missing and not unknown:
        return
    detail = []
    if missing:
        detail.append("es fehlen " + ", ".join(missing))
    if unknown:
        detail.append("unbekannt " + ", ".join(unknown))
    findings.append(f"DRIFT {rel}:{no} controller-names nennt {len(set(items) & known)} von"
                    f" {len(known)} Controllern ({'; '.join(detail)}) | {line.strip()[:120]}")


# ---------------------------------------------------------------------------
# curl with API key without --noproxy (TODO 2e3cf243)
#
# With http_proxy set, curl also sends calls to localhost through the proxy -- including the
# X-API-Key header. The CLAUDE.md snippet therefore has carried `--noproxy '*'` since 6d54a5bf, the
# examples in AGENTS.md and docs/ did not. Every example gets copied; a single one
# without the option is enough for the key to end up at the proxy with the next reader.
#
# The whole call including continuation lines ("\" at the line end) is checked, reported at
# the line with `curl`. Exempt is only a call whose target is explicitly a
# remote instance (https://instanz.example …): there the proxy may be intended.
# Variables like $BASE or $MS count as local -- the examples mean one's own instance.
# ---------------------------------------------------------------------------
CURL_CALL = re.compile(r"(?:^|[\s;|&(`$])curl\s")
# The key as a header argument, not the bare word: "per curl mit X-API-Key" in prose
# is no call.
API_KEY_HEADER = re.compile(r"(?:-H|--header)\s*[\"']?X-API-Key", re.IGNORECASE)
URL_HOST = re.compile(r"https?://(\[[^\]]*\]|[^/\s\"'`:]+)", re.IGNORECASE)
LOCAL_HOSTS = {"localhost", "127.0.0.1", "0.0.0.0", "[::1]"}
# What curl sends is no target: a URL in the body (-d '{"url":"https://hooks.example.com"}')
# would otherwise make the call "entfernt", a localhost in the body make a remote call "lokal".
BODY_ARGUMENT = re.compile(
    r"(?<!\S)(?:-d|-F|--data(?:-raw|-binary|-urlencode|-ascii)?|--json|--form(?:-string)?)(?:=|\s*)"
    r"(?:'[^']*'|\"(?:\\.|[^\"\\])*\"|\S+)")
# --noproxy with a non-empty value; "--noproxy ''" has no effect.
NOPROXY_OPTION = re.compile(r"--noproxy(?:=|\s+)(?:'([^']+)'|\"([^\"]+)\"|([^\s'\"]+))")
# The same effect as an environment variable before the call: NO_PROXY='*' curl …
NOPROXY_ENV = re.compile(r"\b(?:NO_PROXY|no_proxy)=(?:'\*'|\"\*\"|\*)(?:\s|$)")


def curl_command(lines, no):
    """The line no including its continuation lines, joined into one line."""
    parts = [lines[no - 1].rstrip("\n").rstrip()]
    k = no
    while parts[-1].endswith("\\") and k < len(lines):
        parts[-1] = parts[-1][:-1]
        parts.append(lines[k].rstrip("\n").rstrip())
        k += 1
    return " ".join(parts)


def check_curl_noproxy(rel, no, lines, findings):
    line = lines[no - 1]
    # A continuation line belongs to the command of the line before; that one is checked and
    # reported there -- a second curl in it would otherwise count twice.
    if no >= 2 and lines[no - 2].rstrip().endswith("\\"):
        return
    command = curl_command(lines, no)
    if not CURL_CALL.search(command):
        return
    # Per curl call on its own: "curl … --noproxy '*' …; curl …" must not cover the second.
    matches = list(CURL_CALL.finditer(command))
    for k, match in enumerate(matches):
        call = command[match.end():matches[k + 1].start() if k + 1 < len(matches) else len(command)]
        prefix = command[matches[k - 1].end() if k else 0:match.start()]
        if not API_KEY_HEADER.search(call) or NOPROXY_OPTION.search(call) or NOPROXY_ENV.search(prefix):
            continue
        hosts = {host.lower() for host in URL_HOST.findall(BODY_ARGUMENT.sub(" ", call))}
        if hosts and not hosts & LOCAL_HOSTS:
            continue
        findings.append(f"DRIFT {rel}:{no} curl-ohne-noproxy: X-API-Key-Aufruf ohne"
                        f" --noproxy '*' -- mit gesetztem http_proxy ginge der Key ueber den"
                        f" Proxy | {line.strip()[:120]}")
        return


def check_absent_files(rel, no, line, absent, findings):
    for name in absent or ():
        if name in line:
            findings.append(f"DRIFT {rel}:{no} fehlende-datei: '{name}' gibt es im"
                            f" Repository nicht | {line.strip()[:120]}")


def sentence_continues(line):
    """Does the sentence of this line continue into the next?

    Two pieces of evidence, both from the real docs:
    - an open parenthesis (the source and state statement stands in it:
      "73 test classes in the repo (as of\\n2026-09-18, `grep ...`)");
    - or the line ends without punctuation, i.e. mid-sentence.

    Without this check any dated neighbouring line would silence an
    undated test count — exactly what the fixture case
    "Unter src/test/java liegen 715 Testklassen." uncovered: the sentence ends
    with the full stop, the date of the following line belongs to a different statement.
    """
    stripped = line.rstrip()
    if stripped.count("(") > stripped.count(")"):
        return True
    return bool(stripped) and stripped[-1] not in ".!?|"


def check_test_claim_dated(rel, no, line, next_line, findings):
    """A test count needs a date — also one that stands after the line break.

    The docs wrap lines at ~100 characters; the dating regularly ends up
    in the following line. It is then present, just not in
    the same line — so it counts, but only if the sentence actually
    continues there (see sentence_continues).
    """
    dated_next = sentence_continues(line) and ISO_DATE.search(next_line or "")
    if TEST_CLAIM.search(line.strip()) and not ISO_DATE.search(line) and not dated_next:
        findings.append(f"DRIFT {rel}:{no} testzahl-ohne-datum: Testzahlen nur mit Datum"
                        f" (z. B. 'Stand 2026-09-18') | {line.strip()[:120]}")


def check_sizes(rel, no, line, text, boundary, facts, findings):
    """Quantity patterns on text; reported at line no.

    boundary 0: the line pass, every hit counts. Otherwise text is the line plus
    the following line, and only hits count whose number lies before and whose end lies after the
    boundary — so no hit is reported twice.
    """
    is_table = line.lstrip().startswith("|")
    for name, regexes in COMPILED.items():
        expected = facts.get(name)
        if expected is None:
            continue
        if is_table and name in TABLE_EXEMPT:
            continue
        for regex in regexes:
            for match in regex.finditer(text):
                if boundary and not (match.start(1) < boundary < match.end()):
                    continue
                said = int(match.group(1))
                if name == "migration-max" and span_is_declared_excerpt(
                        text, match, expected):
                    continue
                if said != expected:
                    findings.append(
                        f"DRIFT {rel}:{no} {name} sagt {said}, Code {expected}"
                        f" | {line.strip()[:120]}")


def scan(root, rel, facts, findings):
    path = os.path.join(root, rel)
    try:
        with open(path, encoding="utf-8") as fh:
            lines = fh.readlines()
    except OSError:
        return
    stichtage = section_stichtage(lines) if rel == README_SOURCE else None
    for no, line in enumerate(lines, 1):
        # Code blocks are deliberately checked TOO: the directory trees in
        # docs/architektur.md ("├── controller/   43 REST-Controller") stand in
        # ```-blocks and are exactly the places that drift most often.
        if IGNORE_MARKER in line:
            continue
        check_sizes(rel, no, line, line, 0, facts, findings)
        # Two-line window (TODO 880a17ea): "... the 66 built-in" / "templates ..." —
        # the number stands at the line end, the noun in the following line. Only hits that reach
        # across the boundary are checked here; everything else the line pass finds.
        next_line = lines[no] if no < len(lines) else ""
        if next_line and IGNORE_MARKER not in next_line and sentence_continues(line):
            first = line.rstrip("\n").rstrip()
            check_sizes(rel, no, line, first + " " + next_line.lstrip(), len(first), facts,
                        findings)
        check_version_row(rel, no, line, facts.get("versions"), findings)
        if rel == README_SOURCE:
            check_readme_head(rel, no, line, facts, findings, stichtage[no - 1])
        check_forbidden_patterns(rel, no, line, findings)
        check_definition_count(rel, no, lines, findings)
        check_absent_files(rel, no, line, facts.get("absent-files"), findings)
        check_curl_noproxy(rel, no, lines, findings)
        check_block_type_enum(rel, no, line, facts.get("block-type-names"), findings)
        check_controller_enum(rel, no, line, facts.get("controller-names"), findings)
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
