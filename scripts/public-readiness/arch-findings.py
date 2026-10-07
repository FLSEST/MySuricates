#!/usr/bin/env python3
"""arch-findings.py — deterministic grading for the architecture review (TODO b8c06b94).

Reads the two fact sheets (arch-metrics.sh for the backend, frontend-metrics.sh for the
frontend) and prints the findings with their severity. The AI block of the review takes over ruleKey,
subject, severity and evidence unchanged; it explains, orders and prioritises, but does not
grade by itself. Previously the AI set the severity (WorkflowService HIGH despite "Groesse nicht
nachmessbar", i.e. size not remeasurable), and hard line limits produced a treadmill.

Rules (thresholds below as constants, calibrated on 2026-09-26 against the distribution of 1048
backend files and 598 frontend modules; the reasoning is given at each constant):

  Size in NCLOC (ncloc.py: without blank, comment and import lines), in bands instead of limits:
    < 250 nothing | 250-400 observation | > 400 finding MEDIUM | > 600 HIGH
    CRITICAL only for a hotspot with > 600 NCLOC AND > 10 collaborators.
  Hotspot: at least HOTSPOT_MIN_CHANGES changes in 90 days (## files, column aenderungen).
    Size and dependency findings exist ONLY for hotspots; large, stable files are
    at most an observation. The review therefore never reports files under the 300-line limit of
    SourceSizeRatchetTest or on its ALLOWLIST for size alone (the ratchet caps
    them already), only as a hotspot; the ceiling is then given in the evidence.
  Dependencies (collaborators per Spring bean, backend only): from 10 and only for hotspots a
    finding arch.high-coupling, MEDIUM, HIGH with size from the observation band on. 5-9 is no
    finding; from 10 without a hotspot an observation.
  One finding per file, severity is the higher level: arch.high-coupling for a pure
    coupling finding and for a size finding with more than 10 collaborators (switch limit as
    up to 4.5.2, identity stable), otherwise arch.god-class (backend) or arch.god-component (frontend).
  Frontend: size findings only for .tsx components; .ts modules (data, i18n, types, hooks)
    are at most an observation. There is no collaborator count there, so no CRITICAL.
  Package coupling (## coupling, packages and directories): NO finding any more, only a trend number in the report.
  Package cycles (## cycles + ## ratchet): component > 2 packages HIGH, known (all packages in one
    line of CYCLE_COMPONENTS) MEDIUM, CRITICAL if one of their pairs is not in ALLOWED_PAIRS
    (never downgraded). Pair outside a reported component: known MEDIUM, otherwise HIGH.
  Layer edges (## packages): service -> controller CRITICAL, controller -> repository HIGH.
  Frontend import cycles (## cycles, runtime edges only): every component HIGH, > 3 modules CRITICAL.

Candidates for the judgment (TODO e0e9495f, review 4.7.0): ## review_candidates names the files
whose source the AI reads and judges in the review (responsibilities, is a split worth it, does it fit
the place). The script selects, the AI judges; judgment findings carry the ruleKeys from
JUDGMENT_RULE_KEYS and exactly one line of this list as subject. Only backend classes
(.java) and frontend components (.tsx) are chosen:
    hotspot  up to REVIEW_HOTSPOTS files with at least HOTSPOT_MIN_CHANGES changes that have a
             file finding or an observation — findings first, then by changes, NCLOC
    neu      up to REVIEW_NEW files created in the last 7 days (## recent), from
             REVIEW_NEW_MIN_NCLOC NCLOC, by NCLOC
    dublette up to REVIEW_DUPLICATES cross-file duplicates from CPD (--cpd, TODO 67128699), by
             lines; subject is the first file not yet chosen, the other places are in angaben
The column aenderungen_7d tells the report whether the file has changed since the last cycle:
It carries a known judgment on an unchanged file forward instead of making it anew.

Dimensions not measured (sheet missing, jdeps/scan failed, cycles_state != ok,
unresolved imports, no git or shallow clone, NCLOC or collaborator count failed) are listed as not_measured= in
## findings_summary; there are no findings for them, and the report carries forward known findings of that dimension unchanged.

Tools (optional, TODO 67128699 and c608cdda, see arch_tools.py): --cpd <language>=<file> (CSV of pmd cpd,
once per language), --pmd <file> (SARIF fact sheet of pmd check), --jscpd <file> (SARIF fact sheet of jscpd:
the frontend including .tsx, which CPD cannot read) and --lizard <file> (warnings of lizard -w: complexity
per frontend function). They yield ## duplicates (the largest duplicates across files) and ## complexity
(complexity per method, candidates only). Both are facts for the judgement, never findings. Without the
input the section says it was not measured.

Evidence (TODO 2c07ce7e, Review 4.10.0): the platform's evidence check (EvidenceVerification) accepts an
evidence only if it names a FAKT or TREFFER line of a "## FAKTENBLATT" of the same run. So the output ends
with "## FAKTENBLATT arch": one FAKT line per measured finding and candidate, named after the subject
(fact_name) and therefore stable across runs, plus FAKT arch_scope with the measurement scope. The
evidence of every measured finding starts with that name, candidate rows carry it in the column fakt; the
report copies both.

Known judgments (TODO ebf6602e, Review 4.11.0): --known <file> takes the {{ audit_findings }} block of the
platform. For every known judgment finding (JUDGMENT_RULE_KEYS) ## known_judgments says whether the file still
exists according to ## files of this run and how often it changed in 7 days, and the fact sheet gets the
matching FAKT line. A judgment the report carries forward unchanged thus cites a fact of this run, even when
its file is no candidate. Of the block only ruleKey, status and a path-like subject are read: it is data
from AI output and user input.

Usage:   arch-findings.py <backend-sheet> [<frontend-sheet>] [--cpd java=<file>]
                          [--cpd typescript=<file>] [--pmd <file>] [--jscpd <file>] [--lizard <file>]
                          [--known <file>]
         A missing or empty file counts as not measured, no abort.
Output:  ## findings, ## observations, ## review_candidates, ## known_judgments, ## duplicates,
         ## complexity, ## bands, ## findings_summary, ## FAKTENBLATT arch — stably sorted.
Exit:    0 always (fact sheet for a downstream evaluator, no gate); 2 on wrong usage.
"""
import os
import re
import sys

import arch_tools

# NCLOC bands. Today (2026-09-26): backend 13 files from 250, 7 from 300, none above 400 — the
# ratchet (300 raw lines, ceilings up to 450) keeps the backend below the finding band. Frontend
# 11 modules from 250, 7 above 400 (of which 2 .tsx), 4 above 600 (all data: i18n, types, guides).
# 250 is above the bulk (backend 96 %, frontend 93 % below), 400 separates the handful
# of real chunks, 600 the outliers.
BAND_OBSERVE = 250   # observation from here
BAND_FINDING = 400   # above: finding (MEDIUM), hotspot only
BAND_HIGH = 600      # above: HIGH; with > DEPS_CRITICAL collaborators CRITICAL

# Hotspot threshold. 90 days = around 1600 commits; of the changed files, half have
# 1-2 changes. From 5 changes on, around 11 % of the changed backend files remain (93 of
# 847) and 15 % of the frontend files — often touched, but not every one.
HOTSPOT_MIN_CHANGES = 5

# Collaborators: 4 beans have 10 or more today (16, 16, 14, 10), 8 have 8-9 — Spring
# orchestrators with 6-9 narrow services are the normal case, no finding.
DEPS_FINDING = 10    # from here a coupling finding (hotspot only)
DEPS_CRITICAL = 10   # above (so from 11) together with > BAND_HIGH NCLOC: CRITICAL

FRONTEND_CYCLE_CRITICAL = 3  # Frontend-Import-Zyklus mit mehr Modulen: CRITICAL

# Candidates for the judgment. On 2026-10-05, 3 backend files with a finding and around 10
# hotspot observations (.java/.tsx) met the hotspot rule, and in 7 days 136 files were added,
# most of them under 100 NCLOC. Six hotspots and three new files keep the reading at around
# 2500 lines of source per run — enough for a judgment, without reading everything every week.
REVIEW_HOTSPOTS = 6
REVIEW_NEW = 3
REVIEW_NEW_MIN_NCLOC = 100
# On 2026-10-06 CPD (Java, from 100 tokens) found 22 and jscpd (frontend incl. .tsx, from 70 tokens) 49
# cross-file duplicates. The two tools count tokens differently, so each side speaks for itself:
# ten facts per side, and the candidates alternate between backend and frontend.
REVIEW_DUPLICATES = 3
DUPLICATES_LISTED = 10
CPD_LANGUAGES = ("java", "typescript")

# Fact sheet errors after which sizes and hotspots are not measured. Without git history (no
# repo, shallow clone) the changes are missing, hence every hotspot; without NCLOC or collaborator
# count, size or deps are missing. The grading would otherwise look clean, and known findings
# would silently expire.
BACKEND_SIZE_ERRORS = {"source_missing", "no_git_repo", "shallow_history", "ncloc_failed", "collaborators_failed"}
FRONTEND_SIZE_ERRORS = {"no_git_repo", "shallow_history", "ncloc_failed"}

SEVERITY_RANK = {"MEDIUM": 1, "HIGH": 2, "CRITICAL": 3}
RULE_KEYS = ("arch.god-class", "arch.god-component", "arch.high-coupling",
             "arch.cross-layer-violation", "arch.package-cycle")
# Not assigned by the script, but by the AI's judgment over ## review_candidates (at most MEDIUM).
# Prompt and catalog must both bring the lists together (test-arch-findings.sh, section 10).
JUDGMENT_RULE_KEYS = ("arch.mixed-responsibility", "arch.misplaced-logic")
# Fields of an {{ audit_findings }} row that --known reads; anything else in the row is ignored.
KNOWN_STATUS = re.compile(r"^[A-Z_]+$")
KNOWN_SUBJECT = re.compile(r"^[A-Za-z0-9_./-]+$")


def read_sheet(path):
    """{section: [lines without comments]}; None if the file is missing or empty."""
    if not path or path == "-" or not os.path.isfile(path):
        return None
    sections, current = {}, None
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        for raw in handle:
            line = raw.rstrip("\n")
            if line.startswith("## "):
                current = line[3:].strip()
                sections.setdefault(current, [])
            elif current is not None and line and not line.startswith("#"):
                sections[current].append(line)
    return sections or None


def keyvalues(lines):
    result = {}
    for line in lines or []:
        if "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def band(ncloc):
    if ncloc > BAND_HIGH:
        return 3
    if ncloc > BAND_FINDING:
        return 2
    if ncloc >= BAND_OBSERVE:
        return 1
    return 0


def higher(a, b):
    if a is None:
        return b
    if b is None:
        return a
    return a if SEVERITY_RANK[a] >= SEVERITY_RANK[b] else b


def plural(count, one, many):
    return "%d %s" % (count, one if count == 1 else many)


class Classifier:
    def __init__(self):
        self.findings = []       # (ruleKey, subject, severity, evidence)
        self.observations = []   # (art, subject, angaben)
        self.not_measured = []
        self.bands = []          # (seite, [zaehler je band])
        self.files = {}          # subject -> (ncloc, aenderungen, art) mit art befund|beobachtung|None
        self.recent = {}         # subject -> (aenderungen_7d, neu)
        self.recent_missing = [] # Seiten ohne ## recent
        self.cpd_measured = set()  # Sprachen mit lesbarer CPD-Ausgabe
        self.duplicates = []       # (zeilen, tokens, [(subject, zeile)]) ueber Dateigrenzen
        self.duplicates_within = 0
        self.pmd_state = "not_given"
        self.lizard_state = "not_given"
        self.tsx_measured = False
        self.complexity = {}       # subject -> [(zeile, regel, text)]
        self.known = []            # (ruleKey, subject, status) of known judgment findings
        self.known_state = "not_given"

    def tools(self, cpd_texts, pmd_text, jscpd_text=None, lizard_text=None):
        """CPD output per language, jscpd (frontend incl. .tsx), pmd and lizard output (67128699, c608cdda)."""
        sources = [(language, arch_tools.parse_cpd(text)) for language, text in cpd_texts.items()]
        if jscpd_text is not None:
            measured, duplicates = arch_tools.parse_jscpd(jscpd_text)
            self.tsx_measured = measured
            sources.append(("typescript", (measured, duplicates)))
        for language, (measured, duplicates) in sources:
            if not measured:
                continue
            self.cpd_measured.add(language)
            for lines, tokens, places in duplicates:
                if len({subject for subject, _ in places}) > 1:
                    self.duplicates.append((lines, tokens, sorted(places)))
                else:
                    self.duplicates_within += 1
        # By tokens: jscpd reports no line count, both tools report tokens.
        self.duplicates.sort(key=lambda d: (-d[1], -d[0], d[2]))
        if pmd_text is not None:
            self.pmd_state, self.complexity = arch_tools.parse_pmd(pmd_text)
        if lizard_text is not None:
            measured, hits = arch_tools.parse_lizard(lizard_text)
            self.lizard_state = "ok" if measured else "not_measured"
            for subject, rows in hits.items():
                self.complexity.setdefault(subject, []).extend(rows)

    # ------------------------------------------------------------------ Dateien
    def classify_file(self, subject, ncloc, lines, changes, deps, frontend, ceiling):
        hot = changes >= HOTSPOT_MIN_CHANGES
        level = band(ncloc)
        coupled = deps is not None and deps >= DEPS_FINDING
        component = frontend and subject.endswith(".tsx")
        size_rule = component or not frontend

        size_sev = None
        if hot and size_rule and level >= 2:
            size_sev = "MEDIUM"
            if level == 3:
                size_sev = "CRITICAL" if deps is not None and deps > DEPS_CRITICAL else "HIGH"
        coupling_sev = None
        if hot and coupled:
            coupling_sev = "HIGH" if level >= 1 else "MEDIUM"

        parts = ["%d NCLOC (%d Zeilen)" % (ncloc, lines)]
        if deps is not None:
            parts.append("%d deps" % deps)
        parts.append("%s in 90 Tagen (%s ab %d)" % (
            plural(changes, "Aenderung", "Aenderungen"), "Hotspot" if hot else "stabil, Hotspot", HOTSPOT_MIN_CHANGES))
        if ceiling is not None:
            parts.append("Ratchet-Obergrenze %d" % ceiling)
        evidence = ", ".join(parts)

        severity = higher(size_sev, coupling_sev)
        if severity:
            # Switch limit as up to 4.5.2: a size finding only becomes arch.high-coupling from more than DEPS_CRITICAL
            # collaborators, so that its identity stays stable at exactly 10.
            if coupling_sev and (size_sev is None or deps > DEPS_CRITICAL):
                rule = "arch.high-coupling"
            else:
                rule = "arch.god-component" if frontend else "arch.god-class"
            self.findings.append((rule, subject, severity, evidence))
            self.files[subject] = (ncloc, changes, "befund %s %s" % (rule, severity), evidence)
            return
        self.files[subject] = (ncloc, changes, None, evidence)
        if level >= 1 or coupled:
            if coupled and level >= 1:
                kind = "groesse+kopplung"
            elif coupled:
                kind = "kopplung"
            elif frontend and not component:
                kind = "groesse-modul"
            else:
                kind = "groesse"
            self.observations.append((kind, subject, evidence))
            self.files[subject] = (ncloc, changes, "beobachtung " + kind, evidence)

    def classify_files(self, rows, frontend, ceilings):
        counts = [0, 0, 0, 0]
        for ncloc, lines, changes, deps, path in rows:
            counts[band(ncloc)] += 1
            subject = "frontend/src/" + path if frontend else path
            self.classify_file(subject, ncloc, lines, changes, deps, frontend, ceilings.get(path))
        self.bands.append(("frontend" if frontend else "backend", counts))

    def read_recent(self, lines, frontend):
        if lines is None:
            self.recent_missing.append("frontend" if frontend else "backend")
            return
        for line in lines:
            fields = line.split("\t")
            if len(fields) != 3 or not fields[0].isdigit():
                continue
            subject = "frontend/src/" + fields[2] if frontend else fields[2]
            self.recent[subject] = (int(fields[0]), fields[1] == "1")

    # ------------------------------------------------------------------ Backend
    def backend(self, sheet):
        if sheet is None:
            self.not_measured += ["backend_sizes", "backend_cycles", "backend_layers"]
            return
        summary = keyvalues(sheet.get("summary"))
        run = keyvalues(sheet.get("run"))
        errors = set(filter(None, summary.get("error", "").split(",")))
        ratchet = self.ratchet(sheet.get("ratchet"))

        files = parse_files(sheet.get("files"), backend=True)
        if files is None or errors & BACKEND_SIZE_ERRORS:
            self.not_measured.append("backend_sizes")
        else:
            self.classify_files(files, frontend=False, ceilings=ratchet["allowlist"])
            self.read_recent(sheet.get("recent"), frontend=False)

        jdeps_ok = run.get("jdeps") in ("ok", "empty") and not errors & {
            "classes_missing", "jdeps_missing", "jdeps_failed", "jdeps_partial"}
        if not jdeps_ok:
            self.not_measured += ["backend_cycles", "backend_layers"]
            return
        if summary.get("cycles_state") != "ok" and summary.get("cycles_state") != "no_edges":
            self.not_measured.append("backend_cycles")
        else:
            self.backend_cycles(sheet.get("cycles") or [], ratchet)
        self.layers(sheet.get("packages") or [])

    @staticmethod
    def ratchet(lines):
        known = {"allowlist": {}, "pairs": set(), "components": [], "state": "missing"}
        if lines is None:
            return known
        for line in lines:
            fields = line.split("\t")
            if fields[0] == "state" and len(fields) > 1:
                known["state"] = fields[1]
            elif fields[0] == "allowlist" and len(fields) == 3:
                known["allowlist"][fields[2]] = int(fields[1])
            elif fields[0] == "pair" and len(fields) == 3:
                known["pairs"].add(tuple(sorted(fields[1:3])))
            elif fields[0] == "component" and len(fields) == 3:
                known["components"].append(set(fields[2].split(",")))
        return known

    def backend_cycles(self, lines, ratchet):
        components, pairs = parse_cycles(lines)
        pinned = ratchet["state"] == "ok"
        reported = []
        for size, members in components:
            if size <= 2:
                continue
            reported.append(set(members))
            inner = [p for p in pairs if p[0] in members and p[1] in members]
            unknown_pairs = [p for p in inner if not pinned or p not in ratchet["pairs"]]
            home = next((c for c in ratchet["components"] if set(members) <= c), None) if pinned else None
            parts = ["Zyklus-Komponente mit %d Paketen (## cycles)" % size]
            if unknown_pairs:
                severity = "CRITICAL"
                parts.append("neue wechselseitige Paare " + ", ".join("%s<->%s" % p for p in unknown_pairs))
            elif home is not None:
                severity = "MEDIUM"
                parts.append("bekannt und testueberwacht (PackageCycleRatchetTest.CYCLE_COMPONENTS)")
            else:
                severity = "HIGH"
                if not pinned:
                    parts.append("CYCLE_COMPONENTS nicht gefunden, keine Herabstufung")
                else:
                    best = max(ratchet["components"], key=lambda c: len(c & set(members)), default=set())
                    fresh = sorted(set(members) - best)
                    parts.append("neu oder gewachsen, neue Pakete " + ",".join(fresh))
            if inner and not unknown_pairs:
                parts.append("Paare " + ", ".join("%s<->%s" % p for p in inner))
            self.findings.append(("arch.package-cycle", "cycles/component/" + members[0], severity,
                                  "; ".join(parts)))
        for a, b in pairs:
            if any(a in comp and b in comp for comp in reported):
                continue
            allowed = pinned and (a, b) in ratchet["pairs"]
            self.findings.append(("arch.package-cycle", "cycles/pair/%s<->%s" % (a, b),
                                  "MEDIUM" if allowed else "HIGH",
                                  "wechselseitiges Paketpaar (## cycles); " + (
                                      "in PackageCycleRatchetTest.ALLOWED_PAIRS bekannt" if allowed
                                      else "nicht in PackageCycleRatchetTest.ALLOWED_PAIRS, neu")))

    def layers(self, lines):
        for line in lines:
            fields = line.split("\t")
            if len(fields) < 2:
                continue
            source, target = fields[0], fields[1]
            src, dst = source.split("."), target.split(".")
            if "service" in src and "controller" in dst:
                severity = "CRITICAL"
            elif "controller" in src and "repository" in dst:
                severity = "HIGH"
            else:
                continue
            count = fields[2] if len(fields) > 2 else "?"
            self.findings.append(("arch.cross-layer-violation", "layer/%s->%s" % (source, target), severity,
                                  "Schichtkante %s -> %s (## packages, %s Klassenkanten)" % (source, target, count)))

    # ------------------------------------------------------------------ Frontend
    def frontend(self, sheet):
        if sheet is None:
            self.not_measured += ["frontend_sizes", "frontend_cycles"]
            return
        summary = keyvalues(sheet.get("summary"))
        run = keyvalues(sheet.get("run"))
        errors = set(filter(None, summary.get("error", "").split(",")))
        if run.get("scan") != "ok":
            self.not_measured += ["frontend_sizes", "frontend_cycles"]
            return
        files = parse_files(sheet.get("files"), backend=False)
        if files is None or errors & FRONTEND_SIZE_ERRORS:
            self.not_measured.append("frontend_sizes")
        else:
            self.classify_files(files, frontend=True, ceilings={})
            self.read_recent(sheet.get("recent"), frontend=True)
        unresolved = summary.get("unresolved_imports", "0")
        if summary.get("cycles_state") not in ("ok", "no_edges") or unresolved != "0" or errors - FRONTEND_SIZE_ERRORS:
            self.not_measured.append("frontend_cycles")
            return
        components, _ = parse_cycles(sheet.get("cycles") or [])
        for size, members in components:
            severity = "CRITICAL" if size > FRONTEND_CYCLE_CRITICAL else "HIGH"
            self.findings.append(("arch.package-cycle", "cycles/component/frontend/src/" + members[0], severity,
                                  "Import-Zyklus ueber %d Module, nur Laufzeitkanten (## cycles des Frontend-Blatts); "
                                  "%s" % (size, ",".join(members))))

    # ------------------------------------------------------------------ Kandidaten
    def candidates(self):
        """[(art, subject, aenderungen_7d, angaben)]: hotspots first, then new files."""
        def reviewable(subject):
            return subject.endswith(".java") or subject.endswith(".tsx")

        hot = [s for s, (_, changes, kind, _) in self.files.items()
               if reviewable(s) and kind and changes >= HOTSPOT_MIN_CHANGES]
        hot.sort(key=lambda s: (not self.files[s][2].startswith("befund"), -self.files[s][1], -self.files[s][0], s))
        hot = hot[:REVIEW_HOTSPOTS]
        new = [s for s, (_, added) in self.recent.items()
               if added and s in self.files and reviewable(s) and s not in hot
               and self.files[s][0] >= REVIEW_NEW_MIN_NCLOC]
        new.sort(key=lambda s: (-self.files[s][0], s))
        rows = []
        for art, subjects in (("hotspot", hot), ("neu", new[:REVIEW_NEW])):
            for subject in subjects:
                _, _, kind, evidence = self.files[subject]
                details = evidence + ("; " + kind if kind else "")
                rows.append((art, subject, str(self.recent.get(subject, (0, False))[0]), details))
        chosen = {row[1] for row in rows}
        picked = 0
        for lines, tokens, places in self.alternating_duplicates():
            if picked >= REVIEW_DUPLICATES:
                break
            # .ts counts here: a duplicated hook or helper function is exactly the "move" question.
            free = [(s, line) for s, line in places if s not in chosen and (s in self.files or s.endswith(".java"))]
            if not free:
                continue
            subject, line = free[0]
            others = ", ".join("%s:%d" % (s, l) for s, l in places if (s, l) != (subject, line))
            size = "%d Zeilen (%d Tokens)" % (lines, tokens) if lines else "%d Tokens" % tokens
            rows.append(("dublette", subject, str(self.recent.get(subject, (0, False))[0]),
                         "%s doppelt, hier ab Zeile %d; ebenso in %s" % (size, line, others)))
            chosen.add(subject)
            picked += 1
        return rows

    @staticmethod
    def frontend_side(duplicate):
        return duplicate[2][0][0].startswith("frontend/")

    def duplicates_by_side(self):
        backend = [d for d in self.duplicates if not self.frontend_side(d)]
        frontend = [d for d in self.duplicates if self.frontend_side(d)]
        return backend, frontend

    def alternating_duplicates(self):
        """Backend, Frontend, Backend, … — each side in its own order (by tokens)."""
        backend, frontend = self.duplicates_by_side()
        merged = []
        for i in range(max(len(backend), len(frontend))):
            merged += backend[i:i + 1] + frontend[i:i + 1]
        return merged

    # ------------------------------------------------------------------ Known judgments
    def read_known(self, text):
        """Reads the {{ audit_findings }} block: rows "STATUS | SEVERITY | ruleKey | subject | title | Beleg: ..."."""
        if text is None:
            return
        if "<audit_findings>" not in text and "(keine bekannten Befunde)" not in text:
            self.known_state = "unavailable"   # module off, failed injection or an empty file
            return
        self.known_state = "ok"
        known = set()
        for line in text.splitlines():
            # A field never holds "|" (masked as ¦). Platforms before 5fc15f50 end an OPEN row after the title.
            fields = [field.strip() for field in line.split("|")]
            if (len(fields) >= 5 and fields[2] in JUDGMENT_RULE_KEYS
                    and KNOWN_STATUS.match(fields[0]) and KNOWN_SUBJECT.match(fields[3])):
                known.add((fields[2], fields[3], fields[0]))
        self.known = sorted(known, key=lambda k: (k[1], k[0]))

    def known_judgments(self):
        """[(ruleKey, subject, status, existiert, aenderungen_7d)]: each known judgment against this run."""
        rows = []
        for rule, subject, status in self.known:
            side = "frontend" if subject.startswith("frontend/src/") else "backend"
            if side + "_sizes" in self.not_measured:
                exists, changes = "ungeprueft", "nicht gemessen"
            elif subject not in self.files:
                exists, changes = "nein", "-"
            elif side in self.recent_missing:
                exists, changes = "ja", "nicht gemessen"
            else:
                exists, changes = "ja", str(self.recent.get(subject, (0, False))[0])
            rows.append((rule, subject, status, exists, changes))
        return rows

    # ------------------------------------------------------------------ Ausgabe
    def render(self):
        out = ["## findings",
               "# Deterministische Einstufung (scripts/public-readiness/arch-findings.py). Der Report uebernimmt",
               "# jede Zeile unveraendert in Tabelle und Envelope: keine eigene Schwere, keine weiteren Befunde.",
               "# ruleKey\tsubject\tseverity\tevidence"]
        findings = sorted(set(self.findings), key=lambda f: (-SEVERITY_RANK[f[2]], f[0], f[1]))
        out += ["\t".join((rule, subject, severity, "%s: %s" % (fact_name(subject), evidence)))
                for rule, subject, severity, evidence in findings] or ["(keine Befunde)"]
        out += ["", "## observations",
                "# Nur fuers Markdown (Beobachtung, kein Finding): groesse = Band %d-%d NCLOC oder grosse, stabile"
                % (BAND_OBSERVE, BAND_FINDING),
                "# Datei; groesse-modul = .ts-Modul im Frontend; kopplung = ab %d deps ohne Hotspot." % DEPS_FINDING,
                "# art\tsubject\tangaben"]
        observations = sorted(set(self.observations), key=lambda o: (o[1].startswith("frontend/"), o[1]))
        out += ["\t".join(o) for o in observations] or ["(keine Beobachtungen)"]
        candidates = self.candidates()
        out += ["", "## review_candidates",
                "# Dateien fuer das Urteil der KI: bis %d Hotspots (Datei-Befunde zuerst, dann Beobachtungen ab %d"
                % (REVIEW_HOTSPOTS, HOTSPOT_MIN_CHANGES),
                "# Aenderungen) und bis %d in 7 Tagen neu angelegte Dateien ab %d NCLOC, beide nur .java und .tsx;"
                % (REVIEW_NEW, REVIEW_NEW_MIN_NCLOC),
                "# dazu bis %d dateiuebergreifende Duplikate aus CPD (art dublette, auch .ts)." % REVIEW_DUPLICATES,
                "# Urteilsbefunde (%s) nur mit einem subject dieser Liste." % ", ".join(JUDGMENT_RULE_KEYS)]
        gaps = sorted(set(self.not_measured) & {"backend_sizes", "frontend_sizes"})
        missing_cpd = [lang for lang in CPD_LANGUAGES if lang not in self.cpd_measured]
        if gaps:
            out.append("# Groessen nicht gemessen (%s): von dieser Seite keine Kandidaten" % ",".join(gaps))
        if self.recent_missing:
            out.append("# ## recent fehlt (%s): von dieser Seite keine neuen Dateien" % ",".join(sorted(self.recent_missing)))
        out.append("# art\tsubject\taenderungen_7d\tangaben\tfakt (Name fuer den Beleg eines Urteils)")
        out += ["\t".join(c + (fact_name(c[1]),)) for c in candidates] or ["(keine Kandidaten)"]
        known = self.known_judgments()
        out += ["", "## known_judgments",
                "# Bekannte Urteilsbefunde aus BEKANNTE BEFUNDE, gegen diesen Lauf gehalten: existiert laut ## files",
                "# (ungeprueft, wenn die Groessen der Seite nicht gemessen sind), aenderungen_7d aus ## recent.",
                "# Ein weitergefuehrtes Urteil beginnt seine evidence mit fakt."]
        if self.known_state != "ok":
            out.append("# audit_findings %s: keine bekannten Urteile geprueft"
                       % ("nicht gegeben" if self.known_state == "not_given" else "nicht verfuegbar"))
        out.append("# ruleKey\tsubject\tstatus\texistiert\taenderungen_7d\tfakt")
        out += ["\t".join(k + (fact_name(k[1]),)) for k in known] or ["(keine bekannten Urteile)"]
        out += ["", "## duplicates",
                "# Je Seite die %d groessten Duplikate ueber Dateigrenzen, nach Tokens: Backend pmd cpd ab 100 Tokens,"
                % DUPLICATES_LISTED,
                ("# Frontend samt .tsx jscpd ab 70 Tokens (zeilen \"-\": jscpd nennt keine Zeilenzahl)." if self.tsx_measured
                 else "# .tsx ungeprueft (kein jscpd-Lauf, CPD kennt kein TSX), React-Komponenten sind also nicht duplikatfrei."),
                "# zeilen\ttokens\tstellen (subject:zeile, ...)"]
        if missing_cpd:
            out.append("# nicht gemessen: %s" % ",".join(missing_cpd))
        backend, frontend = self.duplicates_by_side()
        out += ["%s\t%d\t%s" % (lines or "-", tokens, ", ".join("%s:%d" % p for p in places))
                for lines, tokens, places in backend[:DUPLICATES_LISTED] + frontend[:DUPLICATES_LISTED]] or ["(keine)"]
        candidate_subjects = [c[1] for c in candidates]
        out += ["", "## complexity",
                "# Nur fuer Kandidaten: Backend pmd check (scripts/public-readiness/pmd-architecture.xml), Frontend lizard",
                "# (Funktionen ab 15 CCN). Fakten fuers Urteil, kein Befund. Zustaende stehen in ## findings_summary.",
                "# subject\tzeile\tregel\ttext"]
        rows = ["%s\t%d\t%s\t%s" % (s, line, rule, text)
                for s in candidate_subjects for line, rule, text in self.complexity.get(s, [])]
        out += rows or ["(keine)"]
        out += ["", "## bands",
                "# NCLOC-Verteilung je Seite (alle Dateien, Frontend ohne Tests)",
                "# seite\tunter_%d\t%d_bis_%d\t%d_bis_%d\tueber_%d" % (
                    BAND_OBSERVE, BAND_OBSERVE, BAND_FINDING, BAND_FINDING + 1, BAND_HIGH, BAND_HIGH)]
        out += ["%s\t%s" % (side, "\t".join(str(c) for c in counts)) for side, counts in self.bands]
        severities = [f[2] for f in findings]
        out += ["", "## findings_summary",
                "findings=%d" % len(findings),
                "critical=%d" % severities.count("CRITICAL"),
                "high=%d" % severities.count("HIGH"),
                "medium=%d" % severities.count("MEDIUM"),
                "observations=%d" % len(observations),
                "review_candidates=%d" % len(candidates),
                "known_judgments=%d" % len(known),
                "known_judgments_state=%s" % self.known_state,
                "duplicates_cross_file=%d" % len(self.duplicates),
                "duplicates_within_file=%d" % self.duplicates_within,
                "duplicates_not_measured=%s" % ",".join(missing_cpd),
                "complexity_state=%s" % self.pmd_state,
                "frontend_complexity_state=%s" % self.lizard_state,
                "band_observe=%d" % BAND_OBSERVE,
                "band_finding=%d" % BAND_FINDING,
                "band_high=%d" % BAND_HIGH,
                "hotspot_min_changes=%d" % HOTSPOT_MIN_CHANGES,
                "deps_finding=%d" % DEPS_FINDING,
                "not_measured=%s" % ",".join(sorted(set(self.not_measured)))]
        out += [""] + self.fact_sheet(findings, candidates, known)
        return "\n".join(out) + "\n"

    def scope(self):
        """What this run measured: dimensions of the grading plus the analysis tools that delivered."""
        dimensions = {"backend_sizes", "backend_cycles", "backend_layers", "frontend_sizes", "frontend_cycles"}
        measured = sorted(dimensions - set(self.not_measured))
        tools = []
        if "java" in self.cpd_measured:
            tools.append("cpd_java")
        if self.tsx_measured:
            tools.append("jscpd")
        elif "typescript" in self.cpd_measured:
            tools.append("cpd_ts")
        if self.pmd_state in ("ok", "truncated"):
            tools.append("pmd_design")
        if self.lizard_state == "ok":
            tools.append("lizard")
        return measured + tools

    def fact_sheet(self, findings, candidates, known=()):
        """## FAKTENBLATT arch: one FAKT line per measured finding, candidate and known judgment, named by fact_name."""
        out = ["## FAKTENBLATT arch",
               "# Je Messbefund, Kandidat und bekanntem Urteil eine FAKT-Zeile; jeder Beleg nennt ihren Namen (Belegpruefung der",
               "# Plattform). arch_scope ist der Messumfang dieses Laufs.",
               "FAKT arch_scope: %s" % (",".join(self.scope()) or "nichts gemessen")]
        facts = {}
        for rule, subject, severity, evidence in findings:
            facts.setdefault(subject, []).append("befund %s %s, %s" % (rule, severity, evidence))
        for art, subject, changes, details in candidates:
            facts.setdefault(subject, []).append("kandidat %s, aenderungen_7d=%s, %s" % (art, changes, details))
        for rule, subject, status, exists, changes in known:
            facts.setdefault(subject, []).append("bekanntes urteil %s %s, existiert %s, aenderungen_7d=%s"
                                                 % (rule, status, exists, changes))
        out += ["FAKT %s: %s" % (fact_name(subject), "; ".join(parts)) for subject, parts in facts.items()]
        return out


def fact_name(subject):
    """Stable fact name for a subject: FAKT names allow letters, digits, _ . - only (EvidenceVerification)."""
    slug = subject.replace("<->", ".and.").replace("->", ".to.")
    return "arch_" + re.sub(r"[^A-Za-z0-9_.-]+", ".", slug).strip(".")


def parse_files(lines, backend):
    """[(ncloc, zeilen, aenderungen, deps|None, pfad)] from ## files; None without a usable line."""
    if lines is None:
        return None
    rows = []
    for line in lines:
        fields = line.split("\t")
        try:
            if backend and len(fields) == 5:
                deps = None if fields[3] == "-" else int(fields[3])
                rows.append((int(fields[0]), int(fields[1]), int(fields[2]), deps, fields[4]))
            elif not backend and len(fields) == 4:
                rows.append((int(fields[0]), int(fields[1]), int(fields[2]), None, fields[3]))
        except ValueError:
            continue
    return rows or None


def parse_cycles(lines):
    components, pairs = [], []
    for line in lines:
        fields = line.split("\t")
        if fields[0] == "cycle" and len(fields) == 3:
            components.append((int(fields[1]), sorted(fields[2].split(","))))
        elif fields[0] == "pair" and len(fields) == 3:
            pairs.append(tuple(sorted(fields[1:3])))
    return components, pairs


USAGE = ("Aufruf: arch-findings.py <backend-faktenblatt> [<frontend-faktenblatt>] [--cpd java=<datei>]"
         " [--cpd typescript=<datei>] [--pmd <datei>] [--jscpd <datei>] [--lizard <datei>] [--known <datei>]\n")


def read_text(path):
    """Content of a tool output; a missing file is empty (= not measured)."""
    if not path or not os.path.isfile(path):
        return ""
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def main(argv):
    sheets, cpd, pmd, jscpd, lizard, known = [], {}, None, None, None, None
    args = list(argv[1:])
    while args:
        arg = args.pop(0)
        if arg in ("--cpd", "--pmd", "--jscpd", "--lizard", "--known") and not args:
            sys.stderr.write(USAGE)
            return 2
        if arg == "--cpd":
            language, _, path = args.pop(0).partition("=")
            if language not in CPD_LANGUAGES or not path:
                sys.stderr.write(USAGE)
                return 2
            cpd[language] = read_text(path)
        elif arg == "--pmd":
            pmd = read_text(args.pop(0))
        elif arg == "--jscpd":
            jscpd = read_text(args.pop(0))
        elif arg == "--lizard":
            lizard = read_text(args.pop(0))
        elif arg == "--known":
            known = read_text(args.pop(0))
        else:
            sheets.append(arg)
    if not 1 <= len(sheets) <= 2:
        sys.stderr.write(USAGE)
        return 2
    classifier = Classifier()
    classifier.backend(read_sheet(sheets[0]))
    classifier.frontend(read_sheet(sheets[1] if len(sheets) > 1 else None))
    classifier.tools(cpd, pmd, jscpd, lizard)
    classifier.read_known(known)
    sys.stdout.write(classifier.render())
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
