#!/usr/bin/env python3
"""arch-findings.py — deterministische Einstufung fuer das Architektur-Review (TODO b8c06b94).

Liest die beiden Faktenblaetter (arch-metrics.sh fuer das Backend, frontend-metrics.sh fuer das
Frontend) und gibt die Befunde samt Schwere aus. Der KI-Block des Reviews uebernimmt ruleKey,
subject, severity und evidence unveraendert; er erklaert, ordnet ein und priorisiert, stuft aber
nicht selbst ein. Vorher legte die KI die Schwere fest (WorkflowService HIGH trotz "Groesse nicht
nachmessbar"), und harte Zeilenkanten erzeugten ein Tretrad.

Regeln (Schwellen unten als Konstanten, kalibriert am 2026-09-26 an der Verteilung von 1048
Backend-Dateien und 598 Frontend-Modulen; die Begruendung steht an jeder Konstante):

  Groesse in NCLOC (ncloc.py: ohne Leer-, Kommentar- und Importzeilen), in Baendern statt Kanten:
    < 250 nichts | 250-400 Beobachtung | > 400 Befund MEDIUM | > 600 HIGH
    CRITICAL nur Hotspot mit > 600 NCLOC UND > 10 Kollaborateuren.
  Hotspot: mindestens HOTSPOT_MIN_CHANGES Aenderungen in 90 Tagen (## files, Spalte aenderungen).
    Groessen- und Abhaengigkeitsbefunde gibt es NUR fuer Hotspots; grosse, stabile Dateien sind
    hoechstens eine Beobachtung. Damit meldet das Review Dateien unter der 300-Zeilen-Grenze von
    SourceSizeRatchetTest oder auf dessen ALLOWLIST nie wegen reiner Groesse (der Ratchet deckelt
    sie schon), sondern nur als Hotspot; die Obergrenze steht dann in der evidence.
  Abhaengigkeiten (Kollaborateure je Spring-Bean, nur Backend): ab 10 und nur fuer Hotspots ein
    Befund arch.high-coupling, MEDIUM, HIGH mit Groesse ab dem Beobachtungsband. 5-9 ist kein
    Befund; ab 10 ohne Hotspot eine Beobachtung.
  Ein Befund je Datei, severity ist die hoehere Stufe: arch.high-coupling fuer einen reinen
    Kopplungsbefund und fuer einen Groessenbefund mit mehr als 10 Kollaborateuren (Wechselgrenze wie
    bis 4.5.2, Identitaet stabil), sonst arch.god-class (Backend) bzw. arch.god-component (Frontend).
  Frontend: Groessenbefunde nur fuer .tsx-Komponenten; .ts-Module (Daten, i18n, Typen, Hooks)
    sind hoechstens eine Beobachtung. Eine Kollaborateur-Zahl gibt es dort nicht, CRITICAL also nicht.
  Paketkopplung (## coupling, Pakete und Verzeichnisse): KEIN Befund mehr, nur Trendzahl im Bericht.
  Paketzyklen (## cycles + ## ratchet): Komponente > 2 Pakete HIGH, bekannt (alle Pakete in einer
    Zeile von CYCLE_COMPONENTS) MEDIUM, CRITICAL wenn eines ihrer Paare nicht in ALLOWED_PAIRS steht
    (nie herabgestuft). Paar ausserhalb einer gemeldeten Komponente: bekannt MEDIUM, sonst HIGH.
  Schichtkanten (## packages): service -> controller CRITICAL, controller -> repository HIGH.
  Frontend-Import-Zyklen (## cycles, nur Laufzeitkanten): jede Komponente HIGH, > 3 Module CRITICAL.

Kandidaten fuer das Urteil (TODO e0e9495f, Review 4.7.0): ## review_candidates nennt die Dateien,
deren Quelltext die KI im Review liest und beurteilt (Verantwortlichkeiten, lohnt ein Schnitt, passt
der Ort). Das Skript waehlt aus, die KI urteilt; Urteilsbefunde tragen die ruleKeys aus
JUDGMENT_RULE_KEYS und als subject genau eine Zeile dieser Liste. Gewaehlt werden nur Backend-Klassen
(.java) und Frontend-Komponenten (.tsx):
    hotspot  bis REVIEW_HOTSPOTS Dateien mit mindestens HOTSPOT_MIN_CHANGES Aenderungen, die einen
             Datei-Befund oder eine Beobachtung haben — Befunde zuerst, dann nach Aenderungen, NCLOC
    neu      bis REVIEW_NEW Dateien, die in den letzten 7 Tagen angelegt wurden (## recent), ab
             REVIEW_NEW_MIN_NCLOC NCLOC, nach NCLOC
    dublette bis REVIEW_DUPLICATES dateiuebergreifende Duplikate aus CPD (--cpd, TODO 67128699), nach
             Zeilen; subject ist die erste noch nicht gewaehlte Datei, die anderen Stellen stehen in angaben
Die Spalte aenderungen_7d sagt dem Report, ob sich die Datei seit dem letzten Takt geaendert hat:
Ein bekanntes Urteil zu einer unveraenderten Datei fuehrt er weiter, statt es neu zu faellen.

Nicht gemessene Dimensionen (Blatt fehlt, jdeps/Scan gescheitert, cycles_state != ok,
unaufgeloeste Importe, kein git oder flacher Klon, NCLOC- oder Kollaborateur-Zaehlung gescheitert) stehen als not_measured= in ## findings_summary; fuer sie gibt
es keine Befunde, und der Report fuehrt bekannte Befunde dieser Dimension unveraendert weiter.

Werkzeuge (optional, TODO 67128699, siehe arch_tools.py): --cpd <sprache>=<datei> (CSV von pmd cpd, je
Sprache einmal) und --pmd <datei> (SARIF-Faktenblatt von pmd check). Daraus ## duplicates (die groessten
dateiuebergreifenden Duplikate) und ## complexity (Komplexitaet je Methode, nur fuer Kandidaten). Beides sind
Fakten fuers Urteil, keine Befunde. Ohne die Eingabe steht der Abschnitt als nicht gemessen da.

Aufruf:  arch-findings.py <backend-faktenblatt> [<frontend-faktenblatt>] [--cpd java=<datei>]
                          [--cpd typescript=<datei>] [--pmd <datei>]
         Eine fehlende oder leere Datei gilt als nicht gemessen, kein Abbruch.
Ausgabe: ## findings, ## observations, ## review_candidates, ## duplicates, ## complexity, ## bands,
         ## findings_summary — stabil sortiert.
Exit:    0 immer (Faktenblatt fuer einen nachgelagerten Auswerter, kein Gate); 2 bei falschem Aufruf.
"""
import os
import sys

import arch_tools

# NCLOC-Baender. Heute (2026-09-26): Backend 13 Dateien ab 250, 7 ab 300, keine ueber 400 — der
# Ratchet (300 rohe Zeilen, Obergrenzen bis 450) haelt das Backend unter dem Befundband. Frontend
# 11 Module ab 250, 7 ueber 400 (davon 2 .tsx), 4 ueber 600 (alles Daten: i18n, Typen, Anleitungen).
# 250 liegt ueber dem Grossteil (Backend 96 %, Frontend 93 % darunter), 400 trennt die Handvoll
# echter Brocken ab, 600 die Ausreisser.
BAND_OBSERVE = 250   # ab hier Beobachtung
BAND_FINDING = 400   # darueber Befund (MEDIUM), nur Hotspot
BAND_HIGH = 600      # darueber HIGH; mit > DEPS_CRITICAL Kollaborateuren CRITICAL

# Hotspot-Schwelle. 90 Tage = rund 1600 Commits; von den geaenderten Dateien hat die Haelfte
# 1-2 Aenderungen. Ab 5 Aenderungen bleiben rund 11 % der geaenderten Backend-Dateien (93 von
# 847) und 15 % der Frontend-Dateien — oft angefasst, aber nicht jede.
HOTSPOT_MIN_CHANGES = 5

# Kollaborateure: 4 Beans haben heute 10 oder mehr (16, 16, 14, 10), 8 haben 8-9 — Spring-
# Orchestratoren mit 6-9 schmalen Services sind der Normalfall, kein Befund.
DEPS_FINDING = 10    # ab hier Kopplungsbefund (nur Hotspot)
DEPS_CRITICAL = 10   # darueber (also ab 11) zusammen mit > BAND_HIGH NCLOC: CRITICAL

FRONTEND_CYCLE_CRITICAL = 3  # Frontend-Import-Zyklus mit mehr Modulen: CRITICAL

# Kandidaten fuer das Urteil. Am 2026-10-05 erfuellten 3 Backend-Dateien mit Befund und rund 10
# Hotspot-Beobachtungen (.java/.tsx) die Hotspot-Regel, und in 7 Tagen kamen 136 Dateien neu dazu,
# die meisten unter 100 NCLOC. Sechs Hotspots und drei neue Dateien halten das Lesen bei rund
# 2500 Zeilen Quelltext je Lauf — genug fuer ein Urteil, ohne jede Woche alles zu lesen.
REVIEW_HOTSPOTS = 6
REVIEW_NEW = 3
REVIEW_NEW_MIN_NCLOC = 100
# CPD fand am 2026-10-06 ab 100 Tokens 22 dateiuebergreifende Java-Duplikate und 19 in .ts-Modulen; die
# drei groessten werden beurteilt, fuenfzehn stehen als Fakt im Bericht.
REVIEW_DUPLICATES = 3
DUPLICATES_LISTED = 15
CPD_LANGUAGES = ("java", "typescript")

# Faktenblatt-Fehler, nach denen Groessen und Hotspots nicht gemessen sind. Ohne Git-Historie (kein
# Repo, flacher Klon) fehlen die Aenderungen, also jeder Hotspot; ohne NCLOC bzw. Kollaborateur-
# Zaehlung fehlen Groesse bzw. deps. Die Einstufung saehe sonst sauber aus, und bekannte Befunde
# liefen still aus.
BACKEND_SIZE_ERRORS = {"source_missing", "no_git_repo", "shallow_history", "ncloc_failed", "collaborators_failed"}
FRONTEND_SIZE_ERRORS = {"no_git_repo", "shallow_history", "ncloc_failed"}

SEVERITY_RANK = {"MEDIUM": 1, "HIGH": 2, "CRITICAL": 3}
RULE_KEYS = ("arch.god-class", "arch.god-component", "arch.high-coupling",
             "arch.cross-layer-violation", "arch.package-cycle")
# Vergibt nicht das Skript, sondern das Urteil der KI ueber ## review_candidates (hoechstens MEDIUM).
# Prompt und Katalog muessen beide Listen zusammen fuehren (test-arch-findings.sh, Abschnitt 10).
JUDGMENT_RULE_KEYS = ("arch.mixed-responsibility", "arch.misplaced-logic")


def read_sheet(path):
    """{abschnitt: [zeilen ohne Kommentare]}; None, wenn die Datei fehlt oder leer ist."""
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
        self.complexity = {}       # subject -> [(zeile, regel, text)]

    def tools(self, cpd_texts, pmd_text):
        """CPD-Ausgaben je Sprache und das pmd-Faktenblatt (TODO 67128699)."""
        for language, text in cpd_texts.items():
            measured, duplicates = arch_tools.parse_cpd(text)
            if not measured:
                continue
            self.cpd_measured.add(language)
            for lines, tokens, places in duplicates:
                if len({subject for subject, _ in places}) > 1:
                    self.duplicates.append((lines, tokens, sorted(places)))
                else:
                    self.duplicates_within += 1
        self.duplicates.sort(key=lambda d: (-d[0], -d[1], d[2]))
        if pmd_text is not None:
            self.pmd_state, self.complexity = arch_tools.parse_pmd(pmd_text)

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
            # Wechselgrenze wie bis 4.5.2: ein Groessenbefund wird erst ab mehr als DEPS_CRITICAL
            # Kollaborateuren zu arch.high-coupling, damit seine Identitaet bei genau 10 stabil bleibt.
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
        """[(art, subject, aenderungen_7d, angaben)]: erst Hotspots, dann neue Dateien."""
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
        for lines, tokens, places in self.duplicates:
            if picked >= REVIEW_DUPLICATES:
                break
            # .ts zaehlt hier mit: eine doppelte Hook- oder Hilfsfunktion ist genau die Frage "verlagern".
            free = [(s, line) for s, line in places if s not in chosen and (s in self.files or s.endswith(".java"))]
            if not free:
                continue
            subject, line = free[0]
            others = ", ".join("%s:%d" % (s, l) for s, l in places if (s, l) != (subject, line))
            rows.append(("dublette", subject, str(self.recent.get(subject, (0, False))[0]),
                         "%d Zeilen (%d Tokens) doppelt, hier ab Zeile %d; ebenso in %s" % (lines, tokens, line, others)))
            chosen.add(subject)
            picked += 1
        return rows

    # ------------------------------------------------------------------ Ausgabe
    def render(self):
        out = ["## findings",
               "# Deterministische Einstufung (scripts/public-readiness/arch-findings.py). Der Report uebernimmt",
               "# jede Zeile unveraendert in Tabelle und Envelope: keine eigene Schwere, keine weiteren Befunde.",
               "# ruleKey\tsubject\tseverity\tevidence"]
        findings = sorted(set(self.findings), key=lambda f: (-SEVERITY_RANK[f[2]], f[0], f[1]))
        out += ["\t".join(f) for f in findings] or ["(keine Befunde)"]
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
        out.append("# art\tsubject\taenderungen_7d\tangaben")
        out += ["\t".join(c) for c in candidates] or ["(keine Kandidaten)"]
        out += ["", "## duplicates",
                "# Copy-Paste-Detector (pmd cpd, ab 100 Tokens), die %d groessten Duplikate ueber Dateigrenzen;"
                % DUPLICATES_LISTED,
                "# .tsx misst CPD nicht (keine TSX-Sprache), React-Komponenten sind also ungeprueft, nicht duplikatfrei.",
                "# zeilen\ttokens\tstellen (subject:zeile, ...)"]
        if missing_cpd:
            out.append("# nicht gemessen: %s" % ",".join(missing_cpd))
        out += ["%d\t%d\t%s" % (lines, tokens, ", ".join("%s:%d" % p for p in places))
                for lines, tokens, places in self.duplicates[:DUPLICATES_LISTED]] or ["(keine)"]
        candidate_subjects = [c[1] for c in candidates]
        out += ["", "## complexity",
                "# pmd check (scripts/public-readiness/pmd-architecture.xml): Methoden ueber der Schwelle, nur fuer",
                "# Backend-Kandidaten. Fakten fuers Urteil, kein Befund. Zustand steht in ## findings_summary.",
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
                "duplicates_cross_file=%d" % len(self.duplicates),
                "duplicates_within_file=%d" % self.duplicates_within,
                "duplicates_not_measured=%s" % ",".join(missing_cpd),
                "complexity_state=%s" % self.pmd_state,
                "band_observe=%d" % BAND_OBSERVE,
                "band_finding=%d" % BAND_FINDING,
                "band_high=%d" % BAND_HIGH,
                "hotspot_min_changes=%d" % HOTSPOT_MIN_CHANGES,
                "deps_finding=%d" % DEPS_FINDING,
                "not_measured=%s" % ",".join(sorted(set(self.not_measured)))]
        return "\n".join(out) + "\n"


def parse_files(lines, backend):
    """[(ncloc, zeilen, aenderungen, deps|None, pfad)] aus ## files; None ohne verwertbare Zeile."""
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
         " [--cpd typescript=<datei>] [--pmd <datei>]\n")


def read_text(path):
    """Inhalt einer Werkzeug-Ausgabe; eine fehlende Datei ist leer (= nicht gemessen)."""
    if not path or not os.path.isfile(path):
        return ""
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def main(argv):
    sheets, cpd, pmd = [], {}, None
    args = list(argv[1:])
    while args:
        arg = args.pop(0)
        if arg in ("--cpd", "--pmd") and not args:
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
        else:
            sheets.append(arg)
    if not 1 <= len(sheets) <= 2:
        sys.stderr.write(USAGE)
        return 2
    classifier = Classifier()
    classifier.backend(read_sheet(sheets[0]))
    classifier.frontend(read_sheet(sheets[1] if len(sheets) > 1 else None))
    classifier.tools(cpd, pmd)
    sys.stdout.write(classifier.render())
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
