#!/usr/bin/env python3
"""Faktenblatt zur Frage, ob jedes Dokument noch sein Gewicht traegt (TODO 05f633c2).

Die Shell misst, die KI urteilt. Dieses Skript entscheidet **nicht**, ob ein Dokument
weg kann — es legt die Signale nebeneinander, aus denen sich das beurteilen laesst.

Warum die naheliegenden Signale allein nicht reichen (am Bestand gemessen, 2026-09-23):

* „kein eingehender Link" trifft 3 von 69 Doku-Dateien — und alle drei sind Fehlalarme:
  `DocsController` liefert sie in der Anwendung aus, `workflow-pool/README.md` zeigt
  GitHub selbst an. Ein reiner md->md-Graph meldet zusaetzlich alle 40+ Ressourcen-
  Dokumente des Backends als verwaist, die der Code per Pfad laedt.
* „lange nicht geaendert" trifft **niemanden**: das Median-Alter liegt bei einem Tag,
  weil Sync- und Formatierungswellen staendig alles anfassen.

Deshalb drei Zonen, drei Kantenarten und ein Pflegesignal, das Wellen herausrechnet:

Zonen
  ROOTS       Einstiege (README, CLAUDE.md, ...) — nie verwaist, Wurzeln der Erreichbarkeit
  SCOPE       docs/** — der Gegenstand der Pruefung
  CODE_OWNED  Ressourcen, die der Code per Pfad laedt — gezaehlt, nie gemeldet

Kanten
  link        [..](pfad) aus einer getrackten .md, fence-bewusst
  mention     Pfad als Zeichenkette in getracktem Nicht-Markdown (Java, TS, Shell, JSON)
  container   ein Konsument zaehlt ein ganzes Verzeichnis auf (DocsController, Generatoren)

`container` unterdrueckt „verwaist" nicht stillschweigend, sondern stuft es zu
„nicht vom Index erreichbar" herab und nennt den Konsumenten als Beleg — ob ein
Eintrag in einem UI-Dropdown echte Erreichbarkeit ist, entscheidet der Mensch.

Run:  python3 scripts/public-readiness/docs_orphan_scan.py [--root <dir>]
Exit: 0 = Faktenblatt geschrieben (auch mit NICHT-GEMESSEN-Zeilen)
      2 = gar nicht gemessen (kein Git-Repo, keine Dateien, Wurzel unbrauchbar)
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from md_common import headings, iter_links, slug, to_de_path, tracked_markdown  # noqa: E402

# ---------------------------------------------------------------------------
# Kalibrierung. Jede Schwelle steht auch als FAKT-Zeile im Blatt, damit die KI
# sieht, worauf die Zahlen geeicht sind, statt es zu raten.
# ---------------------------------------------------------------------------
STALE_WINDOW_DAYS = 180          # Fenster fuer "keine inhaltliche Aenderung"
SUBSTANTIAL_MIN_LINES = 20       # ab so vielen geaenderten Zeilen zaehlt eine Aenderung als inhaltlich
WAVE_MIN_MD_FILES = 10           # ab so vielen .md im selben Commit: Verdacht auf Welle
WAVE_MAX_FILE_LINES = 20         # ... aber nur, wenn an DIESER Datei wenig passierte
RARE_SLUG_MAX_DF = 2             # Ueberschrift gilt als thematisch, wenn sie in <= 2 Dateien steht
OVERLAP_MIN_RARE = 5             # Mindestzahl seltener Ueberschriften je Dokument
OVERLAP_MIN_SHARED = 3           # Mindestzahl geteilter seltener Ueberschriften
OVERLAP_THRESHOLD = 0.40         # Anteil an der kleineren Menge

#: Einstiege. Von hier laeuft die Erreichbarkeitssuche los; nie selbst verwaist.
ROOTS = (
    "README.md", "README.de.md", "CLAUDE.md", "AGENTS.md",
    "CONTRIBUTING.md", "SECURITY.md", "VISION.md",
)

#: Bewusst nicht verlinkte Dokumente. Format: Pfad -> Begruendung mit TODO-Kennung,
#: nach dem Muster von docs_parity_check.KNOWN_GAPS. Ein Eintrag hier erzeugt keinen
#: Befund; ohne die Liste stuende das Gate dauerhaft auf einem bekannten Zustand.
KNOWN_UNLINKED: dict[str, str] = {
    # Verzeichnis-README, das GitHub beim Oeffnen des Ordners selbst anzeigt.
    "workflow-pool/README.md": "kein Doku-Index noetig, GitHub rendert es im Ordner",
}

#: Verzeichnis-Konsumenten: wer zaehlt ganze Ordner auf, ohne einzeln zu verlinken.
#: Je Eintrag (Praefix, Konsument, Literal, das dort stehen muss). Fehlt das Literal,
#: gibt es eine NICHT-GEMESSEN-Zeile statt stillschweigend "keine Kante" — sonst
#: erzeugte ein umbenannter Controller einen Schwall falscher Verwaistheit.
CONTAINER_CONSUMERS = (
    ("docs/", "ai-automation-backend/src/main/java/com/automation/controller/DocsController.java",
     "/api/v1/docs", "DocsController"),
    ("docs/konzepte/", "scripts/concepts/pages.py", "konzepte", "Konzeptseiten-Generator"),
    ("docs/en/konzepte/", "scripts/concepts/pages.py", "konzepte", "Konzeptseiten-Generator"),
)

#: Dateiendungen, in denen ein Pfad als blosse Erwaehnung stehen kann.
MENTION_SUFFIXES = (".java", ".ts", ".tsx", ".js", ".sh", ".py", ".json", ".xml", ".yml", ".yaml")


def fact(name: str, value) -> None:
    print(f"FAKT {name}: {value}")


def missing(name: str, reason: str) -> None:
    print(f"NICHT GEMESSEN: {name} ({reason})")


def hit(name: str, text: str) -> None:
    print(f"TREFFER {name}: {text}")


def git(root: Path, *args: str) -> str:
    result = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True, check=False)
    return result.stdout if result.returncode == 0 else ""


def tracked_files(root: Path) -> list[str]:
    out = git(root, "ls-files")
    return sorted(out.split()) if out.strip() else []


def zones(md_files: list[str]) -> tuple[list[str], list[str]]:
    """Teilt die Markdown-Dateien in (SCOPE, CODE_OWNED).

    CODE_OWNED sind Ressourcen, die der Code per Pfad laedt — Skill-Beschreibungen,
    Prompt-Vorlagen, Onboarding-Texte. Sie gehoeren nicht in einen Doku-Index und
    duerfen nie als verwaist gelten; ohne die Trennung waere das Blatt zu neun
    Zehnteln Rauschen.
    """
    scope, code_owned = [], []
    for rel in md_files:
        if rel.startswith("ai-automation-backend/src/") or rel.startswith("ai-automation-frontend/"):
            code_owned.append(rel)
        elif rel.startswith("docs/") or rel in ROOTS or rel.endswith("/README.md"):
            scope.append(rel)
        else:
            code_owned.append(rel)
    return scope, code_owned


def build_link_graph(root: Path, md_files: list[str]) -> dict[str, set[tuple[str, int]]]:
    """Rueckwaertsgraph der echten Markdown-Links: Ziel -> {(Quelle, Zeile)}."""
    inbound: dict[str, set[tuple[str, int]]] = defaultdict(set)
    for rel in md_files:
        path = root / rel
        if not path.exists():
            continue
        for no, href in iter_links(path):
            path_part, _, _ = href.partition("#")
            if not path_part:
                continue
            try:
                target = (path.parent / path_part).resolve().relative_to(root).as_posix()
            except (ValueError, OSError):
                continue
            if target != rel:
                inbound[target].add((rel, no))
    return inbound


def build_mentions(root: Path, md_files: list[str], all_files: list[str]) -> dict[str, set[str]]:
    """Pfad-Erwaehnungen in getracktem Nicht-Markdown: Ziel -> {Quelldatei}.

    Gesucht wird der **volle Pfad**, nicht der Basename: `architektur.md` steht in zu
    vielen Zusammenhaengen, `docs/architektur.md` ist eindeutig.
    """
    mentions: dict[str, set[str]] = defaultdict(set)
    sources = [f for f in all_files if f.endswith(MENTION_SUFFIXES)]
    if not sources:
        return mentions
    needles = {rel: rel for rel in md_files}
    for src in sources:
        path = root / src
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for rel in needles:
            if rel in text:
                mentions[rel].add(src)
    return mentions


def container_edges(root: Path, md_files: list[str]) -> tuple[dict[str, str], int, list[str]]:
    """Verzeichnis-Kanten: Ziel -> Konsument. Liefert auch ungeprueft gebliebene."""
    edges: dict[str, str] = {}
    verified = 0
    broken: list[str] = []
    for prefix, consumer, literal, label in CONTAINER_CONSUMERS:
        path = root / consumer
        if not path.exists():
            broken.append(f"{label} (Konsument {consumer} fehlt)")
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            broken.append(f"{label} (Konsument {consumer} nicht lesbar)")
            continue
        if literal not in text:
            broken.append(f"{label} (Konsument {consumer} nennt '{literal}' nicht mehr)")
            continue
        verified += 1
        depth_of_prefix = prefix.count("/")
        for rel in md_files:
            # Nur die Ebene direkt unter dem Praefix, nicht rekursiv: DocsController
            # listet docs/*.md, nicht docs/onboarding/*.md.
            if rel.startswith(prefix) and rel.count("/") == depth_of_prefix:
                edges.setdefault(rel, f"{label}:{consumer}")
    return edges, verified, broken


def reachable(md_files: list[str], root_files: list[str], inbound) -> set[str]:
    """Transitive Huelle ab den Einstiegen, ausschliesslich ueber echte Links.

    Nur „hat irgendeinen eingehenden Link" zu fragen, uebersieht die Insel: zwei
    verwaiste Dokumente, die sich gegenseitig verlinken, gelten sonst als erreichbar.
    """
    outbound: dict[str, set[str]] = defaultdict(set)
    for target, sources in inbound.items():
        for src, _ in sources:
            outbound[src].add(target)
    seen = set(root_files)
    stack = list(root_files)
    while stack:
        cur = stack.pop()
        for nxt in outbound.get(cur, ()):
            if nxt not in seen:
                seen.add(nxt)
                stack.append(nxt)
    return seen


def line_count(path: Path) -> int:
    try:
        return len(path.read_text(encoding="utf-8", errors="replace").splitlines())
    except OSError:
        return 0


def maintenance(root: Path, rel: str) -> tuple[str | None, int, int]:
    """(Datum der letzten inhaltlichen Aenderung, inhaltliche Edits, Wellen-Edits).

    Zwei Filter hintereinander, beide am Bestand geeicht:

    1. Eine Welle ist ein Commit, der viele Dokumente gleichzeitig anfasst und an *dieser*
       Datei wenig aendert — i18n-Sync, Umlaut-Korrektur, Kopfzeilen-Stempel. Beide
       Bedingungen sind noetig: ein breiter Commit, der an einer Datei 70 Zeilen aendert,
       ist eine echte Ueberarbeitung und darf nicht als Welle verschwinden.
    2. Auch ausserhalb einer Welle ist eine Dreizeilen-Korrektur keine Pflege. Ohne die
       Substanzschwelle sieht jedes Dokument frisch aus: gemessen am 2026-09-23 faerbte
       ein einziger Tippfehler-Commit die Datei jung, und das Signal fand **niemanden**
       — obwohl `docs/onboarding/GLOSSARY.md` seit 210 Tagen inhaltlich unberuehrt ist.
    """
    log = git(root, "log", "--follow", "-w", "-M", "-C", "--numstat",
              "--format=%H %at", "--", rel)
    if not log.strip():
        return None, 0, 0
    content_edits = wave_edits = 0
    last_content: str | None = None
    commit_ts: int | None = None
    for line in log.splitlines():
        parts = line.split()
        if len(parts) == 2 and len(parts[0]) == 40:
            commit_ts = int(parts[1])
            continue
        fields = line.split("\t")
        if len(fields) < 3 or commit_ts is None:
            continue
        try:
            changed = int(fields[0]) + int(fields[1])
        except ValueError:
            continue  # Binaerdatei ("-")
        breadth = commit_md_breadth(root, line_commit_hash(log, line))
        is_wave = breadth >= WAVE_MIN_MD_FILES and changed <= WAVE_MAX_FILE_LINES
        if is_wave or changed <= SUBSTANTIAL_MIN_LINES:
            wave_edits += 1
        else:
            content_edits += 1
            if last_content is None:
                last_content = datetime.fromtimestamp(commit_ts, timezone.utc).strftime("%Y-%m-%d")
    return last_content, content_edits, wave_edits


_BREADTH_CACHE: dict[str, int] = {}
_COMMIT_OF_LINE: dict[str, str] = {}


def line_commit_hash(log: str, target_line: str) -> str:
    """Commit-Hash zur numstat-Zeile — der zuletzt gesehene Hash im Log."""
    if not _COMMIT_OF_LINE or log not in _COMMIT_OF_LINE.get("__log__", ""):
        _COMMIT_OF_LINE.clear()
        _COMMIT_OF_LINE["__log__"] = log
        current = ""
        for line in log.splitlines():
            parts = line.split()
            if len(parts) == 2 and len(parts[0]) == 40:
                current = parts[0]
            elif line.strip():
                _COMMIT_OF_LINE[line] = current
    return _COMMIT_OF_LINE.get(target_line, "")


def commit_md_breadth(root: Path, commit: str) -> int:
    """Wie viele .md-Dateien beruehrt dieser Commit?"""
    if not commit:
        return 0
    if commit not in _BREADTH_CACHE:
        out = git(root, "show", "--name-only", "--format=", commit)
        _BREADTH_CACHE[commit] = sum(1 for f in out.split() if f.endswith(".md"))
    return _BREADTH_CACHE[commit]


def rare_slug_overlap(root: Path, docs: list[str]) -> list[tuple[str, str, float, int]]:
    """Dokumentpaare mit auffaellig vielen gemeinsamen *thematischen* Ueberschriften.

    Ohne den Haeufigkeitsfilter ist das Signal unbrauchbar: die acht Konzeptseiten sind
    aus einer Vorlage erzeugt und teilen per Konstruktion dieselben Abschnitte — roh
    gemessen ergibt das Dutzende Falschpaare. Nur Ueberschriften, die im ganzen Korpus
    selten sind, tragen ueberhaupt Thema.
    """
    slugs: dict[str, set[str]] = {}
    df: dict[str, int] = defaultdict(int)
    for rel in docs:
        path = root / rel
        if not path.exists():
            continue
        s = {slug(h) for h in headings(path) if h.startswith("##")}
        s.discard("")
        slugs[rel] = s
        for item in s:
            df[item] += 1
    rare = {rel: {s for s in items if df[s] <= RARE_SLUG_MAX_DF} for rel, items in slugs.items()}
    pairs: list[tuple[str, str, float, int]] = []
    names = sorted(rare)
    for i, a in enumerate(names):
        if len(rare[a]) < OVERLAP_MIN_RARE:
            continue
        for b in names[i + 1:]:
            if len(rare[b]) < OVERLAP_MIN_RARE:
                continue
            shared = rare[a] & rare[b]
            if len(shared) < OVERLAP_MIN_SHARED:
                continue
            ratio = len(shared) / min(len(rare[a]), len(rare[b]))
            if ratio >= OVERLAP_THRESHOLD:
                pairs.append((a, b, ratio, len(shared)))
    return sorted(pairs, key=lambda p: (-p[2], p[0], p[1]))


def main() -> int:
    parser = argparse.ArgumentParser(description="Faktenblatt zur Doku-Zehrprobe")
    parser.add_argument("--root", default=None)
    args = parser.parse_args()

    if args.root:
        root = Path(args.root).resolve()
        if not root.is_dir():
            print(f"NICHT GEMESSEN: docs_orphan (Wurzel {args.root} ist kein Verzeichnis)",
                  file=sys.stderr)
            return 2
    else:
        top = subprocess.run(["git", "rev-parse", "--show-toplevel"],
                             capture_output=True, text=True, check=False).stdout.strip()
        if not top:
            print("NICHT GEMESSEN: docs_orphan (kein Git-Repository)", file=sys.stderr)
            return 2
        root = Path(top).resolve()

    md_files = tracked_markdown(root)
    if not md_files:
        print("NICHT GEMESSEN: docs_orphan (keine getrackten Markdown-Dateien)", file=sys.stderr)
        return 2
    all_files = tracked_files(root)

    scope, code_owned = zones(md_files)
    root_files = [r for r in ROOTS if r in set(md_files)]
    inbound = build_link_graph(root, md_files)
    mentions = build_mentions(root, md_files, all_files)
    containers, containers_verified, containers_broken = container_edges(root, md_files)
    reach = reachable(md_files, root_files, inbound)

    lines = {rel: line_count(root / rel) for rel in scope}
    scope_lines = sum(lines.values())

    print("## FAKTENBLATT")
    print("# Deterministisch gemessen. 'FAKT <name>: <wert>' ist ein Messwert,")
    print("# 'NICHT GEMESSEN: <name> (<grund>)' eine offene Luecke — beides ist verbindlich.")
    print()
    print("# Korpus")
    fact("docs_tracked_total", len(md_files))
    fact("docs_scope_files", len(scope))
    fact("docs_scope_lines", scope_lines)
    fact("docs_code_owned_files", len(code_owned))
    fact("docs_roots", len(root_files))

    print()
    print("# Kanten")
    fact("docs_link_edges", sum(len(v) for v in inbound.values()))
    fact("docs_mention_edges", sum(len(v) for v in mentions.values()))
    fact("docs_container_consumers_verified", containers_verified)
    for item in containers_broken:
        missing("container_edge", item)

    print()
    print("# Erreichbarkeit")
    unreachable = [r for r in scope if r not in reach and r not in root_files]
    unreachable_lines = sum(lines.get(r, 0) for r in unreachable)
    fact("docs_reachable_from_roots", len([r for r in scope if r in reach or r in root_files]))
    fact("docs_unreachable_from_roots", len(unreachable))
    fact("docs_unreachable_lines", unreachable_lines)
    share = (unreachable_lines / scope_lines * 100) if scope_lines else 0.0
    fact("docs_unreachable_line_share_pct", f"{share:.1f}")
    fact("docs_inbound_link_zero", len([r for r in scope if not inbound.get(r)]))
    fact("docs_known_unlinked", len(KNOWN_UNLINKED))

    print()
    print("# Pflege")
    now = datetime.now(timezone.utc)
    stale: list[str] = []
    maint: dict[str, tuple[str | None, int, int]] = {}
    for rel in scope:
        last, content_edits, wave_edits = maintenance(root, rel)
        maint[rel] = (last, content_edits, wave_edits)
        if last is None:
            continue
        age = (now - datetime.strptime(last, "%Y-%m-%d").replace(tzinfo=timezone.utc)).days
        if age > STALE_WINDOW_DAYS:
            stale.append(rel)
    fact(f"docs_no_content_edit_{STALE_WINDOW_DAYS}d", len(stale))
    fact("stale_window_days", STALE_WINDOW_DAYS)
    fact("wave_commit_min_md_files", WAVE_MIN_MD_FILES)
    fact("wave_commit_max_file_lines", WAVE_MAX_FILE_LINES)

    print()
    print("# Themen-Ueberlappung")
    overlaps = rare_slug_overlap(root, [r for r in scope if not r.startswith("docs/en/")])
    fact("docs_overlap_pairs", len(overlaps))
    fact("rare_slug_max_df", RARE_SLUG_MAX_DF)
    fact("overlap_threshold", OVERLAP_THRESHOLD)

    print()
    print("# Kandidaten — je Zeile alle Achsen nebeneinander")
    raw = set(unreachable) | set(stale) | {r for r in scope if not inbound.get(r)}
    # Einstiege sind nie Kandidaten: auf README, CLAUDE.md und Geschwister verweist
    # niemand aus dem Repo heraus, sie sind der Anfang und nicht das Ende einer Kette.
    raw -= set(root_files)
    # Die englische Spiegelung ist kein eigenes Dokument. Gibt es die deutsche Fassung,
    # faellt der Spiegel weg — sonst zaehlt jeder Befund doppelt.
    scope_set = set(scope)
    raw = {r for r in raw if not (r.startswith("docs/en/") and to_de_path(r) in scope_set)}
    candidates = sorted(raw, key=lambda r: (-lines.get(r, 0), r))
    fact("docs_candidates", len(candidates))
    for rel in candidates:
        if rel in KNOWN_UNLINKED:
            print(f"bekannt: {rel} ({KNOWN_UNLINKED[rel]})")
            continue
        last, content_edits, wave_edits = maint.get(rel, (None, 0, 0))
        n = lines.get(rel, 0)
        pct = (n / scope_lines * 100) if scope_lines else 0.0
        hit("docs_candidate",
            f"{rel}:1 lines={n} share={pct:.1f}% "
            f"inbound_links={len(inbound.get(rel, ()))} mentions={len(mentions.get(rel, ()))} "
            f"container={containers.get(rel, '-')} reachable={'yes' if rel in reach else 'no'} "
            f"last_content={last or '-'} content_edits={content_edits} wave_edits={wave_edits} "
            f"de_path={to_de_path(rel)}")
    for a, b, ratio, shared in overlaps:
        hit("docs_overlap_pair", f"{a}:1 {b}:1 overlap={ratio:.2f} shared={shared}")

    print()
    print("# Grenzen dieser Messung")
    missing("docs_reader_count", "Zugriffe auf ein Dokument werden nirgends gezaehlt")
    missing("docs_external_inbound", "Verweise von ausserhalb des Repositories sind nicht sichtbar")
    missing("docs_content_correctness", "ob ein Dokument den Code richtig beschreibt, misst doc-facts-check.sh")

    print()
    print("## PFLICHTFELDER")
    print("PFLICHT-FAKTEN: docs_scope_files docs_scope_lines docs_unreachable_from_roots"
          " docs_link_edges docs_container_consumers_verified")
    return 0


if __name__ == "__main__":
    sys.exit(main())
