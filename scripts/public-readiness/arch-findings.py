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
  Ein Befund je Datei: arch.high-coupling, wenn die Kollaborateur-Schwelle erreicht ist, sonst
    arch.god-class (Backend) bzw. arch.god-component (Frontend); severity ist die hoehere Stufe.
  Frontend: Groessenbefunde nur fuer .tsx-Komponenten; .ts-Module (Daten, i18n, Typen, Hooks)
    sind hoechstens eine Beobachtung. Eine Kollaborateur-Zahl gibt es dort nicht, CRITICAL also nicht.
  Paketkopplung (## coupling, Pakete und Verzeichnisse): KEIN Befund mehr, nur Trendzahl im Bericht.
  Paketzyklen (## cycles + ## ratchet): Komponente > 2 Pakete HIGH, bekannt (alle Pakete in einer
    Zeile von CYCLE_COMPONENTS) MEDIUM, CRITICAL wenn eines ihrer Paare nicht in ALLOWED_PAIRS steht
    (nie herabgestuft). Paar ausserhalb einer gemeldeten Komponente: bekannt MEDIUM, sonst HIGH.
  Schichtkanten (## packages): service -> controller CRITICAL, controller -> repository HIGH.
  Frontend-Import-Zyklen (## cycles, nur Laufzeitkanten): jede Komponente HIGH, > 3 Module CRITICAL.

Nicht gemessene Dimensionen (Blatt fehlt, jdeps/Scan gescheitert, cycles_state != ok,
unaufgeloeste Importe, kein git) stehen als not_measured= in ## findings_summary; fuer sie gibt
es keine Befunde, und der Report fuehrt bekannte Befunde dieser Dimension unveraendert weiter.

Aufruf:  arch-findings.py <backend-faktenblatt> [<frontend-faktenblatt>]
         Eine fehlende oder leere Datei gilt als nicht gemessen, kein Abbruch.
Ausgabe: ## findings, ## observations, ## bands, ## findings_summary — stabil sortiert.
Exit:    0 immer (Faktenblatt fuer einen nachgelagerten Auswerter, kein Gate); 2 bei falschem Aufruf.
"""
import os
import sys

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

SEVERITY_RANK = {"MEDIUM": 1, "HIGH": 2, "CRITICAL": 3}
RULE_KEYS = ("arch.god-class", "arch.god-component", "arch.high-coupling",
             "arch.cross-layer-violation", "arch.package-cycle")


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
            if coupling_sev:
                rule = "arch.high-coupling"
            else:
                rule = "arch.god-component" if frontend else "arch.god-class"
            self.findings.append((rule, subject, severity, evidence))
            return
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

    def classify_files(self, rows, frontend, ceilings):
        counts = [0, 0, 0, 0]
        for ncloc, lines, changes, deps, path in rows:
            counts[band(ncloc)] += 1
            subject = "frontend/src/" + path if frontend else path
            self.classify_file(subject, ncloc, lines, changes, deps, frontend, ceilings.get(path))
        self.bands.append(("frontend" if frontend else "backend", counts))

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
        if files is None or "source_missing" in errors or "no_git_repo" in errors:
            self.not_measured.append("backend_sizes")
        else:
            self.classify_files(files, frontend=False, ceilings=ratchet["allowlist"])

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
        if files is None or "no_git_repo" in errors:
            self.not_measured.append("frontend_sizes")
        else:
            self.classify_files(files, frontend=True, ceilings={})
        unresolved = summary.get("unresolved_imports", "0")
        if summary.get("cycles_state") not in ("ok", "no_edges") or unresolved != "0" or errors - {"no_git_repo"}:
            self.not_measured.append("frontend_cycles")
            return
        components, _ = parse_cycles(sheet.get("cycles") or [])
        for size, members in components:
            severity = "CRITICAL" if size > FRONTEND_CYCLE_CRITICAL else "HIGH"
            self.findings.append(("arch.package-cycle", "cycles/component/frontend/src/" + members[0], severity,
                                  "Import-Zyklus ueber %d Module, nur Laufzeitkanten (## cycles des Frontend-Blatts); "
                                  "%s" % (size, ",".join(members))))

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


def main(argv):
    if len(argv) < 2 or len(argv) > 3:
        sys.stderr.write("Aufruf: arch-findings.py <backend-faktenblatt> [<frontend-faktenblatt>]\n")
        return 2
    classifier = Classifier()
    classifier.backend(read_sheet(argv[1]))
    classifier.frontend(read_sheet(argv[2] if len(argv) > 2 else None))
    sys.stdout.write(classifier.render())
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
