#!/usr/bin/env bash
#
# frontend-metrics.sh — deterministische Architektur-Metriken fuer das Frontend.
#
# Schwesterskript von arch-metrics.sh: gleiche Abschnittsnamen, gleiche Lesart, andere
# Messgrundlage. jdeps deckt nur die JVM ab; hier wird der Quelltext gelesen.
#
#   ## packages  Verzeichnisabhaengigkeiten, aus den Modulkanten aggregiert
#   ## cycles    Import-Zyklen auf MODULEBENE (Datei) ueber arch_cycles.py — im Frontend
#                beissen genau die (Temporal Dead Zone bei zirkulaeren ES-Modulen), nicht
#                die auf Verzeichnisebene
#   ## sizes     Zeilen je Datei (Top 30) und je Verzeichnis (Top 15), plus Schwellenzaehler
#   ## churn     geaenderte Frontend-Quelldateien der letzten 90 Tage aus git log
#   ## coupling  ein-/ausgehende Abhaengigkeiten je Verzeichnis und je Modul
#   ## summary   Kennzahlen als key=value
#   ## run       Laufzeit-Zustand (scan=, aliases=) — bewusst NICHT deterministisch
#
# Gemessen wird der Produktionscode unter ai-automation-frontend/src: *.ts und *.tsx ohne
# Testdateien (*.test.*, *.spec.*, __tests__/, __mocks__/, src/test/) und ohne *.d.ts —
# dieselbe Trennung wie im Backend, wo nur src/main/java gemessen wird. Die Zahl der
# ausgeschlossenen Testdateien steht als test_files in ## summary, damit der Ausschluss
# sichtbar bleibt statt zu verschwinden.
#
# Kanten kommen aus `import … from '…'` und `export … from '…'` (beides erzeugt eine echte
# Abhaengigkeit, Barrel-Dateien bestehen nur daraus), mehrzeilige Import-Listen eingeschlossen.
# Reine Seiteneffekt-Importe (`import './x.css'`) haben kein from und sind keine Modulkante.
# Relative Angaben werden auf Modulpfade aufgeloest (.ts/.tsx/index.ts/index.tsx), Aliase aus
# tsconfig.json (compilerOptions.paths) beachtet. Alles, was ausserhalb von src landet oder
# ein Paket ist, zaehlt als external; ein relativer Pfad ohne Datei als unresolved — beide
# stehen in ## summary, damit eine kaputte Aufloesung nicht als Kantenfreiheit durchgeht.
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

ROOT_ARG="${1:-.}"
if [ ! -d "$ROOT_ARG" ]; then
  echo "frontend-metrics: Verzeichnis nicht gefunden: $ROOT_ARG" >&2
  exit 2
fi
cd "$ROOT_ARG" || exit 2
ROOT="$(pwd -P)"
FRONTEND="$ROOT/ai-automation-frontend"
SRC="$FRONTEND/src"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

ERRORS=""
note_error() { ERRORS="${ERRORS:+$ERRORS,}$1"; }

: > "$TMP/scan.tsv"
: > "$TMP/modules.tsv"
: > "$TMP/edges.tsv"
: > "$TMP/diredges.tsv"
: > "$TMP/cycles.tsv"
: > "$TMP/churn.tsv"

# ---------------------------------------------------------------------------
# Quelltext-Scan: ein python3-Aufruf liefert alle Rohdaten als TSV mit Typspalte.
#   module<TAB>pfad<TAB>zeilen<TAB>verzeichnis
#   edge<TAB>von-modul<TAB>nach-modul          (dedupliziert)
#   external<TAB>von-modul<TAB>spezifizierer   (Paket oder ausserhalb von src)
#   asset<TAB>von-modul<TAB>spezifizierer      (aufgeloest, aber kein ts/tsx-Modul)
#   unresolved<TAB>von-modul<TAB>spezifizierer (relativ, zeigt auf nichts)
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
# Import-Listen mitgezaehlt werden.
SPEC = r"""^%s\s(?:[^;'"]|'[^']*'|"[^"]*")*?from\s*['"]([^'"]+)['"]"""
IMPORT_RE = re.compile(SPEC % "import", re.M)
EXPORT_RE = re.compile(SPEC % "export", re.M)

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


edges = set()
for rel in sorted(MODULES):
    text = read(MODULES[rel])
    lines = len(text.splitlines())
    directory = os.path.dirname(rel) or "(src)"
    emit("module", rel, lines, directory)
    specs = IMPORT_RE.findall(text) + EXPORT_RE.findall(text)
    for spec in specs:
        kind, target = resolve(rel, spec)
        if kind == "module":
            if target != rel:
                edges.add((rel, target))
        else:
            emit(kind, rel, target)

for source, target in sorted(edges):
    emit("edge", source, target)

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
awk -F'\t' '$1=="edge" { print $2 "\t" $3 }' "$TMP/scan.tsv" | sort > "$TMP/edges.tsv"

# Verzeichniskanten: Modulkanten auf ihr Verzeichnis aggregiert, Selbstkanten fallen weg
# (gleiche Lesart wie ## packages in arch-metrics.sh).
awk -F'\t' '$1=="module" { dir[$2]=$4 }
            $1=="edge"   { from=dir[$2]; to=dir[$3];
                           if (from != "" && to != "" && from != to) print from "\t" to }' \
    "$TMP/scan.tsv" \
  | sort | uniq -c | awk '{ print $2 "\t" $3 "\t" $1 }' | sort -k1,1 -k2,2 > "$TMP/diredges.tsv"

# Zyklen: ein Fehlschlag der Erkennung darf nicht als "keine Zyklen" durchgehen — das waere
# falsch-gruen in genau der Dimension, fuer die das Faktenblatt gebaut wurde.
CYCLES_STATE="no_edges"
if [ -s "$TMP/edges.tsv" ]; then
  if python3 "$SCRIPT_DIR/arch_cycles.py" < "$TMP/edges.tsv" > "$TMP/cycles.tsv" 2>"$TMP/cycles.err"; then
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

MODULES=$(wc -l < "$TMP/modules.tsv" | tr -d ' ')
EDGES=$(wc -l < "$TMP/edges.tsv" | tr -d ' ')
DIR_EDGES=$(wc -l < "$TMP/diredges.tsv" | tr -d ' ')
FILES_OVER_300=$(awk -F'\t' '$1 > 300' "$TMP/modules.tsv" | wc -l | tr -d ' ')
FILES_OVER_500=$(awk -F'\t' '$1 > 500' "$TMP/modules.tsv" | wc -l | tr -d ' ')
MAX_LINES=$(head -1 "$TMP/modules.tsv" 2>/dev/null | cut -f1)
count_kind() { awk -F'\t' -v k="$1" '$1==k' "$TMP/scan.tsv" | wc -l | tr -d ' '; }
EXTERNAL=$(count_kind external)
ASSETS=$(count_kind asset)
UNRESOLVED=$(count_kind unresolved)
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
printf '# von\tnach\tanzahl (aggregierte Modulkanten)\n'
if [ -s "$TMP/diredges.tsv" ]; then
  cat "$TMP/diredges.tsv"
elif [ "$SCAN_STATE" = "ok" ]; then
  printf '# keine Verzeichnisabhaengigkeiten gefunden\n'
else
  printf '# NICHT GEMESSEN (scan=%s) — siehe error= in ## summary und ## run\n' "$SCAN_STATE"
fi

printf '\n## cycles\n'
printf '# Import-Zyklen auf Modulebene (Datei), erkannt von arch_cycles.py\n'
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
printf '# aenderungen\tdatei\n'
if [ -s "$TMP/churn.tsv" ]; then
  head -30 "$TMP/churn.tsv"
else
  printf '# keine Aenderungen gemessen\n'
fi

printf '\n## coupling\n'
printf '# je Verzeichnis: ausgehende/eingehende Verzeichnisse und Modulkanten, Top 15 nach ausgehend\n'
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

printf '\n## summary\n'
printf 'modules=%s\n' "$MODULES"
printf 'directories=%s\n' "$DIRECTORIES"
printf 'edges=%s\n' "$EDGES"
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
