#!/usr/bin/env bash
#
# frontend-metrics.sh — deterministic architecture metrics for the frontend.
#
# Sister script of arch-metrics.sh: same section names, same reading, different
# measurement basis. jdeps covers only the JVM; here the source text is read.
#
#   ## packages  directory dependencies, aggregated from ALL module edges
#   ## cycles    import cycles at MODULE level (file) via arch_cycles.py, only from the
#                static runtime edges — in the frontend exactly those bite (temporal dead
#                zone with circular ES modules), not those at directory level
#   ## sizes     lines per file (top 30) and per directory (top 15), plus threshold counters
#   ## churn     changed frontend source files of the last 90 days from git log
#   ## recent    changed files of the last 7 days, with a "newly created" flag — input of the
#                candidate selection for the verdict in the review (arch-findings.py, TODO e0e9495f)
#   ## coupling  incoming/outgoing dependencies per directory and per module (since review 4.6.0
#                only a trend number, no finding)
#   ## files     per production module NCLOC (ncloc.py), lines and changes in 90 days —
#                complete, input for arch-findings.py (TODO b8c06b94)
#   ## summary   key figures as key=value
#   ## run       runtime state (scan=, aliases=) — deliberately NOT deterministic
#
# NOTE, two different populations: ## sizes, ## packages, ## cycles and
# ## coupling measure the production code WITHOUT test files; ## churn counts every file under
# ai-automation-frontend/src, i.e. WITH tests (git log does not know the filters). A file from
# ## churn therefore need not appear in ## sizes.
#
# What is measured is the production code under ai-automation-frontend/src: *.ts and *.tsx without
# test files (*.test.*, *.spec.*, __tests__/, __mocks__/, src/test/) and without *.d.ts —
# the same separation as in the backend, where only src/main/java is measured. The number of
# excluded test files appears as test_files in ## summary, so the exclusion
# stays visible instead of disappearing.
#
# Edges come from `import … from '…'`, `export … from '…'` (both create a real
# dependency, barrel files consist only of those) and `import('…')`, multi-line
# import lists included. Pure side-effect imports (`import './x.css'`) have no
# from and are not a module edge.
#
# Every edge carries a kind, because not every dependency exists at runtime:
#   runtime  static value import — binds on load, can build a TDZ cycle
#   type     `import type` / `export type` / only `{ type X }` specifiers — tsc deletes
#            it entirely; a cycle of type edges is none. Counter: type_edges
#   dynamic  `import('…')` (router lazy loading) — deferred, therefore likewise no
#            TDZ cycle, but a real bundling dependency. Counters: dynamic_edges,
#            dynamic_imports (calls)
# If the same module pair matches in several forms, the strongest binding wins.
# ## packages and ## coupling count ALL kinds, ## cycles only runtime.
#
# Relative specifiers are resolved to module paths (.ts/.tsx/index.ts/index.tsx), aliases from
# tsconfig.json (compilerOptions.paths) are respected. Everything that lands outside src or
# is a package counts as external; a relative path without a file as unresolved — both
# appear in ## summary, so a broken resolution does not pass as freedom from edges.
#
# Type positions with import(): `typeof import('m')` and `import('m').Typ` count as a type edge,
# not as a dynamic import — tsc deletes both (TODO 26b3da40). The boundary is drawn at the
# following character: a Promise method (.then/.catch/.finally) is a runtime import.
#
# Known limit: a non-literal specifier (`import(variable)`, paths assembled from strings)
# is not captured — it has no path that could be resolved without a bundler.
# The current frontend has none; should one appear, the edge is silently
# missing. The same holds for `require()` and for imports in comments/strings that
# happen to begin in column 0.
#
# Error paths are reported as "NICHT GEMESSEN", never as a clean result.
#
# Usage:  scripts/public-readiness/frontend-metrics.sh [repo-root]     (default: .)
# Exit:   0 = fact sheet written (even with error= markers), 2 = root unusable
#
# Time budget: pure reading, no compilation — a few seconds. No npm, no node_modules.
#
# Deliberately without common.sh: its run_limited/MAX_LINES would truncate the fact sheet and
# cd_root would ignore the given root.
#
set -uo pipefail
export LC_ALL=C

# Resolve before the cd: if the script was called via a relative path, dirname "$0"
# points nowhere after switching into the measured root — arch_cycles.py would then not
# be found and the cycles silently "not measured".
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
: > "$TMP/recent.tsv"

# ---------------------------------------------------------------------------
# Source scan: one python3 call delivers all raw data as TSV with a type column.
#   module<TAB>path<TAB>lines<TAB>directory
#   edge<TAB>from-module<TAB>to-module<TAB>kind  (deduplicated; kind = runtime|dynamic|type)
#   external<TAB>from-module<TAB>specifier<TAB>kind   (package or outside src)
#   asset<TAB>from-module<TAB>specifier<TAB>kind      (resolved, but not a ts/tsx module)
#   unresolved<TAB>from-module<TAB>specifier<TAB>kind (relative, points to nothing)
#   dyncall<TAB>from-module<TAB>specifier    (per dynamic import() call)
#   alias<TAB>prefix<TAB>target
#   testfile<TAB>path
#   note<TAB>marker                            (e.g. vite_alias_unparsed)
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

# Anchored at the line start, middle part without a semicolon (so a side-effect import does
# not end at the from of the next statement) and across lines, so that multi-line import
# lists are counted too. Group 1 is the clause (for type detection), group 2 the
# specifier.
SPEC = r"""^%s(\s(?:[^;'"]|'[^']*'|"[^"]*")*?)from\s*['"]([^'"]+)['"]"""
IMPORT_RE = re.compile(SPEC % "import", re.M)
EXPORT_RE = re.compile(SPEC % "export", re.M)
# Dynamic import: `import('…')`, also after await/lazy() and in the middle of a line.
# Does not collide with the static patterns above — there `import` is followed by a
# space, here by a parenthesis.
DYNAMIC_RE = re.compile(r"""import\s*\(\s*['"]([^'"]+)['"]\s*\)""")

# Type query: `typeof import('m')` and `import('m').Type` sit in type position, tsc erases
# both completely — that is a type edge, not a dynamic one (TODO 26b3da40). Before, both
# forms counted as dynamic imports, so type_edges, dynamic_edges and dynamic_imports were
# each off by the number of such places.
#
# What separates it from a real runtime import is what follows the parenthesis: a type name
# for a type query, a promise method for a runtime import (`import('m').then(…)`). Hence
# then/catch/finally are excluded; accessing any other property of the promise would make
# no sense. `(await import('m')).default` does not match the pattern, the parenthesis sits
# in between.
TYPE_QUERY_RE = re.compile(
    r"""typeof\s+import\s*\(\s*['"]([^'"]+)['"]\s*\)"""
    r"""|import\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\.\s*(?!then\b|catch\b|finally\b)[A-Za-z_$]""")

# One edge per (from, to); if the same pair occurs in several forms, the strongest binding
# wins: static at runtime > dynamic (deferred) > type only (erased).
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
    # Type queries first: their spans are then blocked for the dynamic pass, otherwise the
    # same place would count twice (once as a type edge, once as a dynamic edge).
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
# Cycle graph: only static runtime edges. tsc deletes type edges, dynamic imports are
# deferred — neither can trigger the temporal dead zone this is about.
awk -F'\t' '$3=="runtime" { print $1 "\t" $2 }' "$TMP/edges.tsv" > "$TMP/runtime-edges.tsv"

# Directory edges: all module edges (also type and dynamic) aggregated onto their directory,
# self-edges are dropped — same reading as ## packages in arch-metrics.sh.
awk -F'\t' '$1=="module" { dir[$2]=$4 }
            $1=="edge"   { from=dir[$2]; to=dir[$3];
                           if (from != "" && to != "" && from != to) print from "\t" to }' \
    "$TMP/scan.tsv" \
  | sort | uniq -c | awk '{ print $2 "\t" $3 "\t" $1 }' | sort -k1,1 -k2,2 > "$TMP/diredges.tsv"

# Cycles: a failure of the detection must not pass as "no cycles" — that would be
# false green in exactly the dimension the fact sheet was built for.
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
# Git churn of the last 90 days
# ---------------------------------------------------------------------------
COMMIT="unknown"
if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  COMMIT=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)
  # A shallow clone (git clone --depth 1) knows only the last commit: every file would then have
  # 0 or 1 changes, and no hotspot would be a measurement gap that looks like cleanliness.
  if [ "$(git -C "$ROOT" rev-parse --is-shallow-repository 2>/dev/null)" = "true" ]; then
    note_error "shallow_history"
  fi
  git -C "$ROOT" log --since=90.days --name-only --format= -- ai-automation-frontend/src 2>/dev/null \
    | grep -v '^$' \
    | sed 's#^ai-automation-frontend/src/##' \
    | sort | uniq -c \
    | awk '{ print $1 "\t" $2 }' \
    | sort -k1,1rn -k2,2 > "$TMP/churn.tsv"
  # The last 7 days (one review cadence) separately, as in arch-metrics.sh: changes per file and whether
  # it was newly created in the window. With test files like ## churn; the candidate selection takes only modules
  # from ## files.
  git -C "$ROOT" log --since=7.days --diff-filter=A --name-only --format= -- ai-automation-frontend/src 2>/dev/null \
    | grep -v '^$' | sed 's#^ai-automation-frontend/src/##' | sort -u > "$TMP/added.txt"
  git -C "$ROOT" log --since=7.days --name-only --format= -- ai-automation-frontend/src 2>/dev/null \
    | grep -v '^$' \
    | sed 's#^ai-automation-frontend/src/##' \
    | sort | uniq -c \
    | awk 'FILENAME == ARGV[1] { added[$1] = 1; next } { print $1 "\t" (($2 in added) ? 1 : 0) "\t" $2 }' \
        "$TMP/added.txt" - \
    | sort -t "$(printf '\t')" -k3,3 > "$TMP/recent.tsv"
else
  note_error "no_git_repo"
fi
CHURN_FILES=$(wc -l < "$TMP/churn.tsv" | tr -d ' ')

# Per module: NCLOC (ncloc.py), lines, changes of the last 90 days — the basis of the
# deterministic grading (arch-findings.py, TODO b8c06b94). Only production modules like
# ## sizes; the changes come from ## churn.
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
# An unread alias (broken tsconfig, resolve.alias in vite.config.ts) silently falsifies
# the resolution — therefore it travels as its own marker in error=.
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

printf '\n## recent\n'
printf '# geaenderte Dateien unter ai-automation-frontend/src der letzten 7 Tage (git log --since=7.days),\n'
printf '# mit Testdateien; neu = im Fenster angelegt (--diff-filter=A). Vollstaendig fuer arch-findings.py;\n'
printf '# der Workflow reicht den Abschnitt nicht an die KI.\n'
printf '# aenderungen\tneu\tdatei\n'
if [ -s "$TMP/recent.tsv" ]; then
  cat "$TMP/recent.tsv"
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

# ## run is deliberately at the end and outside the determinism comparison.
printf '\n## run\n'
printf '# Laufzeit-Zustand, nicht Teil des Determinismus-Vergleichs\n'
printf 'scan=%s\n' "$SCAN_STATE"
printf 'aliases=%s\n' "$ALIASES"
exit 0
