#!/usr/bin/env bash
#
# frontend-metrics.sh — deterministische Architektur-Metriken fuer das Frontend.
#
# Schwesterskript von arch-metrics.sh: gleiche Abschnittsnamen, gleiche Lesart, andere
# Messgrundlage. jdeps deckt nur die JVM ab; hier wird der Quelltext gelesen.
#
#   ## packages  Verzeichnisabhaengigkeiten, aus ALLEN Modulkanten aggregiert
#   ## cycles    Import-Zyklen auf MODULEBENE (Datei) ueber arch_cycles.py, nur aus den
#                statischen Laufzeitkanten — im Frontend beissen genau die (Temporal Dead
#                Zone bei zirkulaeren ES-Modulen), nicht die auf Verzeichnisebene
#   ## sizes     Zeilen je Datei (Top 30) und je Verzeichnis (Top 15), plus Schwellenzaehler
#   ## churn     geaenderte Frontend-Quelldateien der letzten 90 Tage aus git log
#   ## coupling  ein-/ausgehende Abhaengigkeiten je Verzeichnis und je Modul (seit Review 4.6.0
#                nur noch Trendzahl, kein Befund)
#   ## files     je Produktionsmodul NCLOC (ncloc.py), Zeilen und Aenderungen in 90 Tagen —
#                vollstaendig, Eingabe fuer arch-findings.py (TODO b8c06b94)
#   ## summary   Kennzahlen als key=value
#   ## run       Laufzeit-Zustand (scan=, aliases=) — bewusst NICHT deterministisch
#
# ACHTUNG, zwei verschiedene Grundgesamtheiten: ## sizes, ## packages, ## cycles und
# ## coupling messen den Produktionscode OHNE Testdateien; ## churn zaehlt jede Datei unter
# ai-automation-frontend/src, also MIT Tests (git log kennt die Filter nicht). Eine Datei aus
# ## churn muss deshalb nicht in ## sizes stehen.
#
# Gemessen wird der Produktionscode unter ai-automation-frontend/src: *.ts und *.tsx ohne
# Testdateien (*.test.*, *.spec.*, __tests__/, __mocks__/, src/test/) und ohne *.d.ts —
# dieselbe Trennung wie im Backend, wo nur src/main/java gemessen wird. Die Zahl der
# ausgeschlossenen Testdateien steht als test_files in ## summary, damit der Ausschluss
# sichtbar bleibt statt zu verschwinden.
#
# Kanten kommen aus `import … from '…'`, `export … from '…'` (beides erzeugt eine echte
# Abhaengigkeit, Barrel-Dateien bestehen nur daraus) und `import('…')`, mehrzeilige
# Import-Listen eingeschlossen. Reine Seiteneffekt-Importe (`import './x.css'`) haben kein
# from und sind keine Modulkante.
#
# Jede Kante traegt eine Art, denn nicht jede Abhaengigkeit existiert zur Laufzeit:
#   runtime  statischer Wert-Import — bindet beim Laden, kann einen TDZ-Zyklus bauen
#   type     `import type` / `export type` / nur `{ type X }`-Spezifizierer — tsc loescht
#            das restlos; ein Zyklus aus Typkanten ist keiner. Zaehler: type_edges
#   dynamic  `import('…')` (Router-Lazy-Loading) — aufgeschoben, deshalb ebenfalls kein
#            TDZ-Zyklus, aber eine echte Bundling-Abhaengigkeit. Zaehler: dynamic_edges,
#            dynamic_imports (Aufrufe)
# Trifft dasselbe Modulpaar in mehreren Formen zu, gewinnt die staerkste Bindung.
# ## packages und ## coupling zaehlen ALLE Arten, ## cycles nur runtime.
#
# Relative Angaben werden auf Modulpfade aufgeloest (.ts/.tsx/index.ts/index.tsx), Aliase aus
# tsconfig.json (compilerOptions.paths) beachtet. Alles, was ausserhalb von src landet oder
# ein Paket ist, zaehlt als external; ein relativer Pfad ohne Datei als unresolved — beide
# stehen in ## summary, damit eine kaputte Aufloesung nicht als Kantenfreiheit durchgeht.
#
# Typpositionen mit import(): `typeof import('m')` und `import('m').Typ` zaehlen als Typkante,
# nicht als dynamischer Import — tsc loescht beides (TODO 26b3da40). Abgegrenzt wird am
# Folgezeichen: eine Promise-Methode (.then/.catch/.finally) ist ein Laufzeit-Import.
#
# Bekannte Grenze: ein nicht-literaler Spezifizierer (`import(variable)`, aus Strings
# zusammengesetzte Pfade) wird nicht erfasst — er hat keinen Pfad, den man ohne Bundler
# aufloesen koennte. Im heutigen Frontend kommt das nicht vor; sollte es auftreten, fehlt die
# Kante lautlos. Gleiches gilt fuer `require()` und fuer Importe in Kommentaren/Strings, die
# zufaellig in Spalte 0 beginnen.
#
# Fehlerpfade werden als "NICHT GEMESSEN" ausgewiesen, nie als sauberes Ergebnis.
#
# Usage:  scripts/public-readiness/frontend-metrics.sh [repo-root]     (Default: .)
# Exit:   0 = Faktenblatt geschrieben (auch mit error=-Markierungen), 2 = Wurzel unbrauchbar
#
# Zeitbudget: reines Lesen, keine Kompilierung — wenige Sekunden. Kein npm, kein node_modules.
#
# Bewusst ohne common.sh: dessen run_limited/MAX_LINES wuerde das Faktenblatt kuerzen und
# cd_root die uebergebene Wurzel ignorieren.
#
set -uo pipefail
export LC_ALL=C

# Vor dem cd aufloesen: wurde das Skript ueber einen relativen Pfad aufgerufen, zeigt
# dirname "$0" nach dem Wechsel in die gemessene Wurzel ins Leere — arch_cycles.py waere
# dann nicht auffindbar und die Zyklen still "nicht gemessen".
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ROOT_ARG="${1:-.}"
if [ ! -d "$ROOT_ARG" ]; then
  echo "frontend-metrics: Verzeichnis nicht gefunden: $ROOT_ARG" >&2
  exit 2
fi
cd "$ROOT_ARG" || exit 2
ROOT="$(pwd -P)"
FRONTEND="$ROOT/ai-automation-frontend"
SRC="$FRONTEND/src"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

ERRORS=""
note_error() { ERRORS="${ERRORS:+$ERRORS,}$1"; }

: > "$TMP/scan.tsv"
: > "$TMP/modules.tsv"
: > "$TMP/edges.tsv"
: > "$TMP/runtime-edges.tsv"
: > "$TMP/diredges.tsv"
: > "$TMP/cycles.tsv"
: > "$TMP/churn.tsv"

# ---------------------------------------------------------------------------
# Quelltext-Scan: ein python3-Aufruf liefert alle Rohdaten als TSV mit Typspalte.
#   module<TAB>pfad<TAB>zeilen<TAB>verzeichnis
#   edge<TAB>von-modul<TAB>nach-modul<TAB>art  (dedupliziert; art = runtime|dynamic|type)
#   external<TAB>von-modul<TAB>spezifizierer<TAB>art   (Paket oder ausserhalb von src)
#   asset<TAB>von-modul<TAB>spezifizierer<TAB>art      (aufgeloest, aber kein ts/tsx-Modul)
#   unresolved<TAB>von-modul<TAB>spezifizierer<TAB>art (relativ, zeigt auf nichts)
#   dyncall<TAB>von-modul<TAB>spezifizierer    (je dynamischer import()-Aufruf)
#   alias<TAB>praefix<TAB>ziel
#   testfile<TAB>pfad
#   note<TAB>marke                             (z. B. vite_alias_unparsed)
# ---------------------------------------------------------------------------
SCAN_STATE="ok"
if [ ! -d "$SRC" ]; then
  SCAN_STATE="no_source"
  note_error "source_missing"
elif ! command -v python3 >/dev/null 2>&1; then
  SCAN_STATE="no_python"
  note_error "python_missing"
else
  if python3 - "$FRONTEND" > "$TMP/scan.tsv" 2>"$TMP/scan.err" <<'PY'
import json
import os
import re
import sys

frontend = sys.argv[1]
src = os.path.join(frontend, "src")

MODULE_EXT = (".ts", ".tsx")
TEST_DIR = re.compile(r"(^|/)(__tests__|__mocks__|test)/")
TEST_FILE = re.compile(r"\.(test|spec)\.[jt]sx?$")

# Anker am Zeilenanfang, Mittelteil ohne Semikolon (so endet ein Seiteneffekt-Import
# nicht am from der naechsten Anweisung) und ueber Zeilen hinweg, damit mehrzeilige
# Import-Listen mitgezaehlt werden. Gruppe 1 ist die Klausel (fuer die Typerkennung),
# Gruppe 2 der Spezifizierer.
SPEC = r"""^%s(\s(?:[^;'"]|'[^']*'|"[^"]*")*?)from\s*['"]([^'"]+)['"]"""
IMPORT_RE = re.compile(SPEC % "import", re.M)
EXPORT_RE = re.compile(SPEC % "export", re.M)
# Dynamischer Import: `import('…')`, auch hinter await/lazy() und mitten in der Zeile.
# Kollidiert nicht mit den statischen Mustern oben — dort folgt auf `import` ein
# Leerzeichen, hier eine Klammer.
DYNAMIC_RE = re.compile(r"""import\s*\(\s*['"]([^'"]+)['"]\s*\)""")

# Typ-Query: `typeof import('m')` und `import('m').Typ` stehen in Typposition, tsc loescht
# beides restlos — das ist eine Typkante, keine dynamische (TODO 26b3da40). Vorher zaehlten
# beide Formen als dynamischer Import, wodurch type_edges, dynamic_edges und dynamic_imports
# je um die Zahl dieser Stellen daneben lagen.
#
# Die Abgrenzung zum echten Laufzeit-Import haengt daran, was auf die Klammer folgt: bei
# einer Typ-Query ein Typname, bei einem Laufzeit-Import eine Promise-Methode
# (`import('m').then(…)`). Deshalb sind then/catch/finally ausgenommen; ein Zugriff auf eine
# andere Eigenschaft der Promise waere sinnlos. `(await import('m')).default` trifft das
# Muster nicht, dort steht die Klammer dazwischen.
TYPE_QUERY_RE = re.compile(
    r"""typeof\s+import\s*\(\s*['"]([^'"]+)['"]\s*\)"""
    r"""|import\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\.\s*(?!then\b|catch\b|finally\b)[A-Za-z_$]""")

# Eine Kante je (von, nach); trifft dasselbe Paar in mehreren Formen zu, gewinnt die
# staerkste Bindung: statisch zur Laufzeit > dynamisch (aufgeschoben) > nur Typ (geloescht).
EDGE_RANK = {"runtime": 3, "dynamic": 2, "type": 1}


def type_only(clause):
    """True, wenn die Klausel zur Laufzeit nichts bindet — tsc loescht sie restlos."""
    text = " ".join(clause.split())
    if text == "type" or text.startswith("type "):
        return True  # import type X / import type { … } / export type { … }
    if text.startswith("{") and text.endswith("}"):
        inner = text[1:-1].strip()
        if not inner:
            return True
        return all(part.strip().startswith("type ") for part in inner.split(",") if part.strip())
    return False

out = []


def emit(*fields):
    out.append("\t".join(str(f) for f in fields))


def read(path):
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def aliases():
    """(praefix, ziel relativ zu src) aus tsconfig.json; Ziele ausserhalb von src entfallen."""
    found = []
    config = os.path.join(frontend, "tsconfig.json")
    if not os.path.isfile(config):
        return found
    try:
        raw = re.sub(r"(?m)^\s*//[^\n]*$", "", read(config))
        options = json.loads(raw).get("compilerOptions") or {}
    except (ValueError, OSError):
        emit("note", "tsconfig_unparsed")
        return found
    base = options.get("baseUrl") or "."
    for prefix, targets in (options.get("paths") or {}).items():
        if not prefix.endswith("/*") or not targets or not str(targets[0]).endswith("/*"):
            continue
        target = os.path.normpath(os.path.join(base, str(targets[0])[:-2]))
        if target == "src":
            found.append((prefix[:-2], ""))
        elif target.startswith("src" + os.sep):
            found.append((prefix[:-2], target[len("src" + os.sep):]))
    return sorted(set(found))


def vite_alias_note():
    """Ein resolve.alias in vite.config.ts wuerde hier ungesehen mitwirken — dann lieber melden."""
    config = os.path.join(frontend, "vite.config.ts")
    if os.path.isfile(config) and re.search(r"(?m)^\s*alias\s*:", read(config)):
        emit("note", "vite_alias_unparsed")


def collect():
    modules = {}
    tests = []
    for current, dirs, files in os.walk(src):
        dirs[:] = sorted(d for d in dirs if d != "node_modules")
        for name in sorted(files):
            path = os.path.join(current, name)
            rel = os.path.relpath(path, src).replace(os.sep, "/")
            if not name.endswith(MODULE_EXT) or name.endswith(".d.ts"):
                continue
            if TEST_DIR.search(rel) or TEST_FILE.search(name):
                tests.append(rel)
                continue
            modules[rel] = path
    return modules, tests


ALIASES = aliases()
vite_alias_note()
MODULES, TESTS = collect()

for prefix, target in ALIASES:
    emit("alias", prefix, target or "(src)")
for rel in TESTS:
    emit("testfile", rel)


def resolve(origin, spec):
    """(art, ziel) fuer einen Spezifizierer, gesehen aus dem Modul origin."""
    if spec.startswith("."):
        base = os.path.normpath(os.path.join(os.path.dirname(origin), spec)).replace(os.sep, "/")
    else:
        base = None
        for prefix, target in ALIASES:
            if spec == prefix or spec.startswith(prefix + "/"):
                rest = spec[len(prefix):].lstrip("/")
                base = os.path.normpath(os.path.join(target, rest)).replace(os.sep, "/")
                break
        if base is None:
            return ("external", spec)
    if base.startswith("..") or base == ".":
        return ("external", spec)
    for candidate in (base, base + ".ts", base + ".tsx", base + "/index.ts", base + "/index.tsx"):
        if candidate in MODULES:
            return ("module", candidate)
    if os.path.exists(os.path.join(src, base)):
        return ("asset", spec)
    return ("unresolved", spec)


edges = {}


def record(origin, spec, kind):
    resolved, target = resolve(origin, spec)
    if resolved != "module":
        emit(resolved, origin, target, kind)
        return
    if target == origin:
        return
    previous = edges.get((origin, target))
    if previous is None or EDGE_RANK[kind] > EDGE_RANK[previous]:
        edges[(origin, target)] = kind


for rel in sorted(MODULES):
    text = read(MODULES[rel])
    lines = len(text.splitlines())
    directory = os.path.dirname(rel) or "(src)"
    emit("module", rel, lines, directory)
    for clause, spec in IMPORT_RE.findall(text) + EXPORT_RE.findall(text):
        record(rel, spec, "type" if type_only(clause) else "runtime")
    # Typ-Queries zuerst: ihre Fundstellen sind danach fuer den dynamischen Durchgang gesperrt,
    # sonst zaehlte dieselbe Stelle zweimal (einmal als Typ-, einmal als dynamische Kante).
    type_query_spans = []
    for match in TYPE_QUERY_RE.finditer(text):
        record(rel, match.group(1) or match.group(2), "type")
        type_query_spans.append((match.start(), match.end()))
    for match in DYNAMIC_RE.finditer(text):
        if any(start <= match.start() < end for start, end in type_query_spans):
            continue
        emit("dyncall", rel, match.group(1))
        record(rel, match.group(1), "dynamic")

for (source, target) in sorted(edges):
    emit("edge", source, target, edges[(source, target)])

sys.stdout.write("\n".join(out) + ("\n" if out else ""))
PY
  then
    if [ ! -s "$TMP/scan.tsv" ]; then
      SCAN_STATE="empty"
      note_error "scan_empty"
    fi
  else
    SCAN_STATE="failed"
    note_error "scan_failed"
    : > "$TMP/scan.tsv"
  fi
fi

awk -F'\t' '$1=="module" { print $3 "\t" $2 "\t" $4 }' "$TMP/scan.tsv" \
  | sort -k1,1rn -k2,2 > "$TMP/modules.tsv"
awk -F'\t' '$1=="edge" { print $2 "\t" $3 "\t" $4 }' "$TMP/scan.tsv" | sort > "$TMP/edges.tsv"
# Zyklengraph: nur statische Laufzeitkanten. Typkanten loescht tsc, dynamische Importe sind
# aufgeschoben — beide koennen die Temporal Dead Zone nicht ausloesen, um die es hier geht.
awk -F'\t' '$3=="runtime" { print $1 "\t" $2 }' "$TMP/edges.tsv" > "$TMP/runtime-edges.tsv"

# Verzeichniskanten: alle Modulkanten (auch Typ- und dynamische) auf ihr Verzeichnis
# aggregiert, Selbstkanten fallen weg — gleiche Lesart wie ## packages in arch-metrics.sh.
awk -F'\t' '$1=="module" { dir[$2]=$4 }
            $1=="edge"   { from=dir[$2]; to=dir[$3];
                           if (from != "" && to != "" && from != to) print from "\t" to }' \
    "$TMP/scan.tsv" \
  | sort | uniq -c | awk '{ print $2 "\t" $3 "\t" $1 }' | sort -k1,1 -k2,2 > "$TMP/diredges.tsv"

# Zyklen: ein Fehlschlag der Erkennung darf nicht als "keine Zyklen" durchgehen — das waere
# falsch-gruen in genau der Dimension, fuer die das Faktenblatt gebaut wurde.
CYCLES_STATE="no_edges"
if [ -s "$TMP/runtime-edges.tsv" ]; then
  if python3 "$SCRIPT_DIR/arch_cycles.py" < "$TMP/runtime-edges.tsv" > "$TMP/cycles.tsv" 2>"$TMP/cycles.err"; then
    CYCLES_STATE="ok"
  else
    CYCLES_STATE="failed"
    note_error "cycles_failed"
    : > "$TMP/cycles.tsv"
  fi
fi

# ---------------------------------------------------------------------------
# Git-Churn der letzten 90 Tage
# ---------------------------------------------------------------------------
COMMIT="unknown"
if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  COMMIT=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)
  # Ein flacher Klon (git clone --depth 1) kennt nur den letzten Commit: jede Datei haette dann
  # 0 oder 1 Aenderung, und kein Hotspot waere eine Messluecke, die wie Sauberkeit aussieht.
  if [ "$(git -C "$ROOT" rev-parse --is-shallow-repository 2>/dev/null)" = "true" ]; then
    note_error "shallow_history"
  fi
  git -C "$ROOT" log --since=90.days --name-only --format= -- ai-automation-frontend/src 2>/dev/null \
    | grep -v '^$' \
    | sed 's#^ai-automation-frontend/src/##' \
    | sort | uniq -c \
    | awk '{ print $1 "\t" $2 }' \
    | sort -k1,1rn -k2,2 > "$TMP/churn.tsv"
else
  note_error "no_git_repo"
fi
CHURN_FILES=$(wc -l < "$TMP/churn.tsv" | tr -d ' ')

# Je Modul: NCLOC (ncloc.py), Zeilen, Aenderungen der letzten 90 Tage — die Grundlage der
# deterministischen Einstufung (arch-findings.py, TODO b8c06b94). Nur Produktionsmodule wie
# ## sizes; die Aenderungen stammen aus ## churn.
: > "$TMP/files.tsv"
if [ -s "$TMP/modules.tsv" ]; then
  cut -f2 "$TMP/modules.tsv" | sort > "$TMP/module-list.txt"
  if python3 "$SCRIPT_DIR/ncloc.py" "$SRC" < "$TMP/module-list.txt" > "$TMP/ncloc.tsv" 2>"$TMP/ncloc.err"; then
    awk -F'\t' 'FILENAME == ARGV[1] { churn[$2] = $1; next }
                { c = ($3 in churn) ? churn[$3] : 0; print $1 "\t" $2 "\t" c "\t" $3 }' \
        "$TMP/churn.tsv" "$TMP/ncloc.tsv" \
      | sort -t "$(printf '\t')" -k1,1rn -k4,4 > "$TMP/files.tsv"
  else
    note_error "ncloc_failed"
  fi
fi

MODULES=$(wc -l < "$TMP/modules.tsv" | tr -d ' ')
EDGES=$(wc -l < "$TMP/edges.tsv" | tr -d ' ')
count_edge_kind() { awk -F'\t' -v k="$1" '$3==k' "$TMP/edges.tsv" | wc -l | tr -d ' '; }
RUNTIME_EDGES=$(count_edge_kind runtime)
TYPE_EDGES=$(count_edge_kind type)
DYNAMIC_EDGES=$(count_edge_kind dynamic)
DIR_EDGES=$(wc -l < "$TMP/diredges.tsv" | tr -d ' ')
FILES_OVER_300=$(awk -F'\t' '$1 > 300' "$TMP/modules.tsv" | wc -l | tr -d ' ')
FILES_OVER_500=$(awk -F'\t' '$1 > 500' "$TMP/modules.tsv" | wc -l | tr -d ' ')
MAX_LINES=$(head -1 "$TMP/modules.tsv" 2>/dev/null | cut -f1)
count_kind() { awk -F'\t' -v k="$1" '$1==k' "$TMP/scan.tsv" | wc -l | tr -d ' '; }
EXTERNAL=$(count_kind external)
ASSETS=$(count_kind asset)
UNRESOLVED=$(count_kind unresolved)
DYNAMIC_IMPORTS=$(count_kind dyncall)
TEST_FILES=$(count_kind testfile)
ALIASES=$(count_kind alias)
CYCLES=$(grep -c '^cycle' "$TMP/cycles.tsv" 2>/dev/null); CYCLES=${CYCLES:-0}
PAIRS=$(grep -c '^pair' "$TMP/cycles.tsv" 2>/dev/null); PAIRS=${PAIRS:-0}
DIRECTORIES=$(cut -f3 "$TMP/modules.tsv" | sort -u | wc -l | tr -d ' ')
[ "$UNRESOLVED" -gt 0 ] && note_error "unresolved_imports"
# Ein nicht gelesener Alias (tsconfig kaputt, resolve.alias in vite.config.ts) faelscht die
# Aufloesung still — deshalb wandert er als eigene Marke in error=.
NOTES=$(awk -F'\t' '$1=="note" { print $2 }' "$TMP/scan.tsv" | sort -u | tr '\n' ',' | sed 's/,$//')
[ -n "$NOTES" ] && note_error "$NOTES"

# ---------------------------------------------------------------------------
# Ausgabe
# ---------------------------------------------------------------------------
printf '## packages\n'
printf '# Verzeichnisabhaengigkeiten unter ai-automation-frontend/src, ohne Selbstkanten\n'
printf '# von\tnach\tanzahl (aggregierte Modulkanten, alle Arten: runtime+type+dynamic)\n'
if [ -s "$TMP/diredges.tsv" ]; then
  cat "$TMP/diredges.tsv"
elif [ "$SCAN_STATE" = "ok" ]; then
  printf '# keine Verzeichnisabhaengigkeiten gefunden\n'
else
  printf '# NICHT GEMESSEN (scan=%s) — siehe error= in ## summary und ## run\n' "$SCAN_STATE"
fi

printf '\n## cycles\n'
printf '# Import-Zyklen auf Modulebene (Datei), erkannt von arch_cycles.py\n'
printf '# NUR statische Laufzeitkanten: Typkanten loescht tsc, dynamische Importe sind\n'
printf '# aufgeschoben — beide koennen keinen TDZ-Zyklus bauen (siehe type_edges/dynamic_edges)\n'
printf '# cycle\tgroesse\tmodule   |   pair\ta\tb (wechselseitiger Import)\n'
if [ -s "$TMP/cycles.tsv" ]; then
  cat "$TMP/cycles.tsv"
elif [ "$CYCLES_STATE" = "ok" ]; then
  printf '# keine Import-Zyklen zwischen Modulen gefunden\n'
else
  printf '# NICHT GEMESSEN (cycles_state=%s) — kein Beleg fuer Zyklenfreiheit\n' "$CYCLES_STATE"
fi

printf '\n## sizes\n'
printf '# Zeilen je Modul unter ai-automation-frontend/src, Top 30 absteigend\n'
printf '# zeilen\tmodul\n'
if [ -s "$TMP/modules.tsv" ]; then
  head -30 "$TMP/modules.tsv" | cut -f1,2
else
  printf '# NICHT GEMESSEN (scan=%s)\n' "$SCAN_STATE"
fi
printf 'files=%s\nfiles_over_300=%s\nfiles_over_500=%s\n' "$MODULES" "$FILES_OVER_300" "$FILES_OVER_500"
printf '# Zeilen je Verzeichnis (Summe), Top 15 absteigend\n'
printf '# zeilen\tmodule\tverzeichnis\n'
if [ -s "$TMP/modules.tsv" ]; then
  awk -F'\t' '{ lines[$3]+=$1; files[$3]++ }
              END { for (d in lines) printf "%d\t%d\t%s\n", lines[d], files[d], d }' "$TMP/modules.tsv" \
    | sort -k1,1rn -k3,3 | head -15
else
  printf '# NICHT GEMESSEN (scan=%s)\n' "$SCAN_STATE"
fi

printf '\n## churn\n'
printf '# geaenderte Dateien unter ai-automation-frontend/src der letzten 90 Tage, Top 30\n'
printf '# andere Grundgesamtheit als ## sizes: git log kennt keine Testfilter, hier sind\n'
printf '# Testdateien enthalten\n'
printf '# aenderungen\tdatei\n'
if [ -s "$TMP/churn.tsv" ]; then
  head -30 "$TMP/churn.tsv"
else
  printf '# keine Aenderungen gemessen\n'
fi

printf '\n## coupling\n'
printf '# je Verzeichnis: ausgehende/eingehende Verzeichnisse und Modulkanten (alle Arten),\n'
printf '# Top 15 nach ausgehend\n'
printf '# verzeichnis\tausgehend\teingehend\tausgehende_kanten\teingehende_kanten\n'
if [ -s "$TMP/diredges.tsv" ]; then
  awk -F'\t' '{ out_dir[$1]++; out_w[$1]+=$3; in_dir[$2]++; in_w[$2]+=$3; seen[$1]=1; seen[$2]=1 }
              END { for (d in seen) printf "%s\t%d\t%d\t%d\t%d\n", d, out_dir[d]+0, in_dir[d]+0, out_w[d]+0, in_w[d]+0 }' \
      "$TMP/diredges.tsv" | sort -k2,2rn -k4,4rn -k1,1 | head -15
else
  printf '# NICHT GEMESSEN (scan=%s)\n' "$SCAN_STATE"
fi
printf '# je Modul: ausgehende/eingehende Importe, Top 15 nach eingehend\n'
printf '# modul\tausgehend\teingehend\n'
if [ -s "$TMP/edges.tsv" ]; then
  awk -F'\t' '{ out_m[$1]++; in_m[$2]++; seen[$1]=1; seen[$2]=1 }
              END { for (m in seen) printf "%s\t%d\t%d\n", m, out_m[m]+0, in_m[m]+0 }' "$TMP/edges.tsv" \
    | sort -k3,3rn -k2,2rn -k1,1 | head -15
else
  printf '# NICHT GEMESSEN (scan=%s)\n' "$SCAN_STATE"
fi

printf '\n## files\n'
printf '# je Produktionsmodul: NCLOC (ohne Leer-, Kommentar- und Importzeilen), Zeilen, Aenderungen der\n'
printf '# letzten 90 Tage, absteigend nach NCLOC. Vollstaendig fuer arch-findings.py; der Workflow reicht\n'
printf '# den Abschnitt nicht an die KI.\n'
printf '# ncloc\tzeilen\taenderungen\tmodul\n'
if [ -s "$TMP/files.tsv" ]; then
  cat "$TMP/files.tsv"
else
  printf '# NICHT GEMESSEN (scan=%s)\n' "$SCAN_STATE"
fi

printf '\n## summary\n'
printf 'modules=%s\n' "$MODULES"
printf 'directories=%s\n' "$DIRECTORIES"
printf 'edges=%s\n' "$EDGES"
printf 'runtime_edges=%s\n' "$RUNTIME_EDGES"
printf 'type_edges=%s\n' "$TYPE_EDGES"
printf 'dynamic_edges=%s\n' "$DYNAMIC_EDGES"
printf 'dynamic_imports=%s\n' "$DYNAMIC_IMPORTS"
printf 'dir_edges=%s\n' "$DIR_EDGES"
printf 'cycles=%s\n' "$CYCLES"
printf 'cycle_pairs=%s\n' "$PAIRS"
printf 'cycles_state=%s\n' "$CYCLES_STATE"
printf 'files=%s\n' "$MODULES"
printf 'files_over_300=%s\n' "$FILES_OVER_300"
printf 'files_over_500=%s\n' "$FILES_OVER_500"
printf 'max_file_lines=%s\n' "${MAX_LINES:-0}"
printf 'test_files=%s\n' "$TEST_FILES"
printf 'external_imports=%s\n' "$EXTERNAL"
printf 'asset_imports=%s\n' "$ASSETS"
printf 'unresolved_imports=%s\n' "$UNRESOLVED"
printf 'churn_files_90d=%s\n' "$CHURN_FILES"
printf 'commit=%s\n' "$COMMIT"
[ -n "$ERRORS" ] && printf 'error=%s\n' "$ERRORS"

# ## run steht bewusst am Ende und ausserhalb des Determinismus-Vergleichs.
printf '\n## run\n'
printf '# Laufzeit-Zustand, nicht Teil des Determinismus-Vergleichs\n'
printf 'scan=%s\n' "$SCAN_STATE"
printf 'aliases=%s\n' "$ALIASES"
exit 0
