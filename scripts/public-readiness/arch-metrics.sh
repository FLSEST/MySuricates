#!/usr/bin/env bash
#
# arch-metrics.sh — deterministic architecture metrics for the architecture review.
#
# Measures with built-in tools (JDK/git/coreutils/python3, no new dependencies):
#   ## packages  package dependencies from `jdeps -verbose:class` (com.automation.* only)
#   ## cycles    cycles between packages (Tarjan in arch_cycles.py) — the same pair candidates
#                are also in the allowlist of PackageCycleRatchetTest.ALLOWED_PAIRS,
#                the cycle components with more than two packages in its CYCLE_COMPONENTS (the
#                size of the largest in MAX_CYCLE_COMPONENT_SIZE), both under
#                ai-automation-backend/src/test/java/com/automation/architecture/. Whoever
#                renames or moves a package must update these lists in the same commit —
#                otherwise the ratchet fails on the next full test run although nothing about
#                the actual cycle changed.
#   ## sizes     lines per Java file, top 30, plus threshold counters (300/500)
#   ## churn     files changed in the last 90 days from git log
#   ## recent    files changed in the last 7 days, flagged "newly created" — input for the
#                candidate selection for the review verdict (arch-findings.py, TODO e0e9495f)
#   ## coupling  incoming/outgoing package dependencies, top 15 by outgoing (since review 4.6.0
#                only a trend figure, not a finding)
#   ## files     per file NCLOC (ncloc.py), lines, changes in 90 days and collaborators per
#                Spring bean — complete, input for arch-findings.py (TODO b8c06b94)
#   ## ratchet   ALLOWLIST of SourceSizeRatchetTest, ALLOWED_PAIRS and CYCLE_COMPONENTS of
#                PackageCycleRatchetTest, machine-readable
#   ## summary   key figures as key=value
#   ## run       runtime state (compile=reused|cached|compiled|…, jdeps=) — deliberately NOT
#                deterministic
#
# The output is a fact sheet for an AI block that only interprets — it is
# stably sorted and contains no timestamps.
#
# Determinism, precisely: everything before `## run` is byte-identical between two runs,
# as long as (a) HEAD is the same, (b) both runs fall into the same 90- or 7-day window
# (i.e. no commit slips across the boundary) and (c) the measured build belongs to the same
# source state. The runtime state is therefore under `## run`: `compile=` switches
# on the first run in a fresh working directory from `compiled` to `reused` or
# `cached`; that is no measurement difference and must not turn a determinism
# comparison red.
#
# Usage:  scripts/public-readiness/arch-metrics.sh [repo-root]        (default: .)
# Env:    ARCH_METRICS_COMPILE=0   never compile, only use an existing target/classes
#         AUTOMATION_ARCH_CACHE    build cache (default: ~/.cache/mysuricates/arch-classes)
#         ARCH_METRICS_CLASSES_OUT file the path of the measured build is written to
#                                  (target/classes or cache entry) — for arch-seams.py, which then
#                                  reads the same bytecode without rebuilding the cache rules. Not
#                                  in the fact sheet: that stays free of absolute paths.
# Exit:   0 = fact sheet written (also with error= markers), 2 = root unusable
#
# Time budget: if ai-automation-backend/target/classes is missing, a one-off
# `./mvnw -q -DskipTests compile` runs (~1-2 minutes on the dev host); jdeps and the counters
# then need ~10-20 seconds. Hence timeout_seconds: 600 in the workflow.
#
# Build cache: a workspace clone (isolated run) never has a target/classes and would
# recompile every time. If $AUTOMATION_ARCH_CACHE/<commit-sha>/ already holds a
# build of the measured commit, it is measured directly from there (compile=cached); otherwise
# it is compiled and the result stored there (compile=compiled). The key is `git rev-parse
# HEAD`, and only with a clean working tree — with uncommitted changes a hit would
# be a wrong measurement. The three most recent entries are kept. Every failure of the
# cache is silent: then it is simply measured fresh.
#
# Parallel runs: a used entry is freshly stamped (mtime) on a hit, so that
# cleanup does not pull it away in the middle of the jdeps measurement, and an already valid
# entry is never replaced. Two runs of the same commit thus do not get in each
# other's way; at worst one discards its freshly copied staging.
#
# Known residual risk (TODO 26b3da40): cleanup only keeps the most recent CACHE_KEEP=3
# entries and locks none that is currently being read. If FOUR runs measure a different
# commit each at the same time, the fourth can evict the entry of an active reader. The
# fresh stamping on a hit makes the case unlikely (the active entry is then the
# most recent), but does not rule it out. The consequence would be a measurement failure, not a wrong
# number: jdeps no longer finds the classes and the run reports that as NOT MEASURED.
# Four simultaneous architecture measurements of different commits do not occur in today's
# operation (the audits run one after another on a schedule); if it happens, the answer
# is a lock file per entry, not a larger CACHE_KEEP.
#
# Deliberately without common.sh: its run_limited/MAX_LINES would truncate the fact sheet and
# cd_root would ignore the passed root.
#
set -uo pipefail
export LC_ALL=C

# Resolve before the cd: if the script was called via a relative path, dirname "$0"
# points nowhere after changing into the measured root — arch_cycles.py would then
# not be found and the cycles silently "not measured".
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ROOT_ARG="${1:-.}"
if [ ! -d "$ROOT_ARG" ]; then
  echo "arch-metrics: Verzeichnis nicht gefunden: $ROOT_ARG" >&2
  exit 2
fi
cd "$ROOT_ARG" || exit 2
ROOT="$(pwd -P)"
BACKEND="$ROOT/ai-automation-backend"
SRC="$BACKEND/src/main/java"
CLASSES="$BACKEND/target/classes"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

ERRORS=""
note_error() { ERRORS="${ERRORS:+$ERRORS,}$1"; }

: > "$TMP/edges.tsv"
: > "$TMP/cycles.tsv"
: > "$TMP/sizes.tsv"
: > "$TMP/churn.tsv"
: > "$TMP/recent.tsv"

# ---------------------------------------------------------------------------
# Provide classes (jdeps needs bytecode, not source)
# ---------------------------------------------------------------------------
CACHE_ROOT="${AUTOMATION_ARCH_CACHE:-${HOME:-$TMP}/.cache/mysuricates/arch-classes}"
CACHE_KEEP=3

# The cache key is the measured commit — and only if the working state matches it.
# With uncommitted changes a hit would be a wrong measurement: the commit would be measured,
# the working state reported. No repo, no HEAD, dirty tree ⇒ no cache.
cache_key() {
  git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1 || return 1
  local sha
  sha=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null) || return 1
  [ -n "$sha" ] || return 1
  [ -z "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ] || return 1
  printf '%s' "$sha"
}

has_classes() { [ -n "$(find "$1" -name '*.class' -print -quit 2>/dev/null)" ]; }

# Only the most recent CACHE_KEEP entries are kept; one build per commit would otherwise grow
# over the months into a silent gigabyte in the home directory. Dot directories
# (staging of a parallel run) do not count and are not touched.
prune_cache() {
  find "$CACHE_ROOT" -mindepth 1 -maxdepth 1 -type d ! -name '.*' -printf '%T@\t%p\n' 2>/dev/null \
    | sort -rn \
    | awk -v keep="$CACHE_KEEP" -F'\t' 'NR > keep { print $2 }' \
    | while IFS= read -r stale; do
        case "$stale" in "$CACHE_ROOT"/?*) rm -rf "$stale" ;; esac
      done
}

# A hit is used, so freshly stamped: jdeps reads from it for 10-20 s, and the
# keep-3 ranking goes by mtime — without it a parallel run could clean the entry up right
# during the measurement and we would measure half a class tree.
touch_cache() { touch "$1" 2>/dev/null || true; }

# Storing is optional: every failure (permissions, full disk, unusable path) stays silent,
# the fact sheet is unaffected.
store_cache() {  # $1 = key, $2 = source directory
  [ -n "$1" ] || return 0
  has_classes "$2" || return 0
  local entry="$CACHE_ROOT/$1"
  # A valid entry is never replaced — a hit is a hit, and a parallel
  # run might be measuring from it right now. Only a half-written remainder is cleaned up.
  if has_classes "$entry"; then
    touch_cache "$entry"
    prune_cache
    return 0
  fi
  local staging="$CACHE_ROOT/.staging.$$"
  mkdir -p "$CACHE_ROOT" 2>/dev/null || return 0
  rm -rf "$staging"
  cp -r "$2" "$staging" 2>/dev/null || { rm -rf "$staging"; return 0; }
  rm -rf "${CACHE_ROOT:?}/$1"
  mv "$staging" "$entry" 2>/dev/null || { rm -rf "$staging"; return 0; }
  prune_cache
}

COMPILE_STATE="reused"
if [ ! -d "$CLASSES" ]; then
  CACHE_KEY="$(cache_key)" || CACHE_KEY=""
  if [ -n "$CACHE_KEY" ] && [ -d "$CACHE_ROOT/$CACHE_KEY" ] && has_classes "$CACHE_ROOT/$CACHE_KEY"; then
    # Measure directly from the cache instead of copying back: jdeps only reads, and target/classes
    # thus stays what a real build leaves there. Stamp first, so the
    # entry is not cleaned up by a parallel run during the measurement.
    CLASSES="$CACHE_ROOT/$CACHE_KEY"
    touch_cache "$CLASSES"
    COMPILE_STATE="cached"
  elif [ "${ARCH_METRICS_COMPILE:-1}" != "1" ]; then
    COMPILE_STATE="skipped"
  elif [ -x "$BACKEND/mvnw" ] && [ -f "$BACKEND/pom.xml" ]; then
    echo "arch-metrics: target/classes fehlt — kompiliere (Zeitbudget ca. 1-2 Minuten)" >&2
    if ( cd "$BACKEND" && ./mvnw -q -DskipTests compile ) >&2; then
      COMPILE_STATE="compiled"
      store_cache "$CACHE_KEY" "$CLASSES"
    else
      COMPILE_STATE="failed"
    fi
  else
    COMPILE_STATE="unavailable"
  fi
fi
[ -d "$CLASSES" ] || note_error "classes_missing"

# ---------------------------------------------------------------------------
# Package edges from jdeps: class edges are aggregated at package level,
# self edges (package onto itself) are dropped.
# ---------------------------------------------------------------------------
JDEPS_STATE="ok"
if ! command -v jdeps >/dev/null 2>&1; then
  JDEPS_STATE="missing"
  note_error "jdeps_missing"
elif [ -d "$CLASSES" ]; then
  # Collect raw first, then evaluate: in a pipe the return value of jdeps would be
  # lost and a failure would look like "no dependencies".
  jdeps -verbose:class -filter:none "$CLASSES" > "$TMP/jdeps.raw" 2>"$TMP/jdeps.err"
  JDEPS_RC=$?
  awk 'NF==4 && $2=="->" && $1 ~ /^com\.automation\./ && $3 ~ /^com\.automation\./ {
         from=$1; to=$3;
         sub(/\.[^.]*$/, "", from); sub(/\.[^.]*$/, "", to);
         if (from != to) print from "\t" to
       }' "$TMP/jdeps.raw" \
    | sort | uniq -c \
    | awk '{ print $2 "\t" $3 "\t" $1 }' \
    | sort -k1,1 -k2,2 > "$TMP/edges.tsv"
  if [ "$JDEPS_RC" -ne 0 ]; then
    if [ -s "$TMP/edges.tsv" ]; then
      JDEPS_STATE="partial"
      note_error "jdeps_partial"
    else
      JDEPS_STATE="failed"
      note_error "jdeps_failed"
      : > "$TMP/edges.tsv"
    fi
  elif [ ! -s "$TMP/edges.tsv" ]; then
    JDEPS_STATE="empty"
  fi
else
  JDEPS_STATE="no_classes"
fi

# Cycles: a failure of the detection must not pass as "no cycles" — that would be
# a false green in exactly the dimension the fact sheet was built for.
CYCLES_STATE="no_edges"
if [ -s "$TMP/edges.tsv" ]; then
  if cut -f1,2 "$TMP/edges.tsv" | python3 "$SCRIPT_DIR/arch_cycles.py" > "$TMP/cycles.tsv" 2>"$TMP/cycles.err"; then
    CYCLES_STATE="ok"
  else
    CYCLES_STATE="failed"
    note_error "cycles_failed"
    : > "$TMP/cycles.tsv"
  fi
fi

# ---------------------------------------------------------------------------
# File sizes — same counting as SourceSizeRatchetTest
# (Files.readAllLines(..).size(): awk also counts a last line without a newline).
# Empty files yield no record in awk and would be missing — they are added via find -empty
# with 0 lines, so that the line count of the list matches files=.
# ---------------------------------------------------------------------------
FILES=0
if [ -d "$SRC" ]; then
  ( cd "$SRC" \
      && find . -type f -name '*.java' ! -empty -print0 \
        | xargs -0 -r awk 'FNR==1 { if (f != "") { sub(/^\.\//, "", f); print n "\t" f } f=FILENAME } { n=FNR }
                           END { if (f != "") { sub(/^\.\//, "", f); print n "\t" f } }' \
      && find . -type f -name '*.java' -empty -printf '0\t%P\n' ) \
    | sort -k1,1rn -k2,2 > "$TMP/sizes.tsv"
  FILES=$(find "$SRC" -type f -name '*.java' | wc -l | tr -d ' ')
else
  note_error "source_missing"
fi
FILES_OVER_300=$(awk -F'\t' '$1 > 300' "$TMP/sizes.tsv" | wc -l | tr -d ' ')
FILES_OVER_500=$(awk -F'\t' '$1 > 500' "$TMP/sizes.tsv" | wc -l | tr -d ' ')

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
  git -C "$ROOT" log --since=90.days --name-only --format= -- ai-automation-backend/src/main/java 2>/dev/null \
    | grep -v '^$' \
    | sed 's#^ai-automation-backend/src/main/java/##' \
    | sort | uniq -c \
    | awk '{ print $1 "\t" $2 }' \
    | sort -k1,1rn -k2,2 > "$TMP/churn.tsv"
  # The last 7 days (one review cycle) separately: changes per file and whether it was newly
  # created in the window. The candidate selection for the verdict (arch-findings.py) takes new classes from it.
  git -C "$ROOT" log --since=7.days --diff-filter=A --name-only --format= -- ai-automation-backend/src/main/java 2>/dev/null \
    | grep -v '^$' | sed 's#^ai-automation-backend/src/main/java/##' | sort -u > "$TMP/added.txt"
  git -C "$ROOT" log --since=7.days --name-only --format= -- ai-automation-backend/src/main/java 2>/dev/null \
    | grep -v '^$' \
    | sed 's#^ai-automation-backend/src/main/java/##' \
    | sort | uniq -c \
    | awk 'FILENAME == ARGV[1] { added[$1] = 1; next } { print $1 "\t" (($2 in added) ? 1 : 0) "\t" $2 }' \
        "$TMP/added.txt" - \
    | sort -t "$(printf '\t')" -k3,3 > "$TMP/recent.tsv"
else
  note_error "no_git_repo"
fi
CHURN_FILES=$(wc -l < "$TMP/churn.tsv" | tr -d ' ')

# ---------------------------------------------------------------------------
# Per file: NCLOC (ncloc.py), collaborators per Spring bean, changes — the basis
# of the deterministic grading (arch-findings.py, TODO b8c06b94).
# ---------------------------------------------------------------------------
: > "$TMP/ncloc.tsv"
: > "$TMP/deps.tsv"
if [ -d "$SRC" ]; then
  ( cd "$SRC" && find . -type f -name '*.java' | sed 's#^\./##' | sort ) > "$TMP/java-files.txt"
  if ! python3 "$SCRIPT_DIR/ncloc.py" "$SRC" < "$TMP/java-files.txt" > "$TMP/ncloc.tsv" 2>"$TMP/ncloc.err"; then
    note_error "ncloc_failed"
    : > "$TMP/ncloc.tsv"
  fi
  # Collaborators = injected fields "private final <type> <name>;" (without initializer, not
  # static) of the outer class of a Spring bean. These types are no collaborators but
  # tool, configuration value or state — the only exclusion list. List, Map and Set are
  # deliberately not on it: without an initializer such a field is almost always a bean collection
  # injected via constructor (List<BlockExecutor>), state has an initializer.
  # Counted are the lines starting at bracket depth 1, in the body of the top-level type; a field
  # of an inner class (WorkflowStatsService.RunStatistics.runs) is state of a value object.
  # Brackets in comments (also after https:// in Javadoc), literals and text blocks do not count.
  # Up to 4.5.2 this counting ran in the delta_scan block of the workflow; here it is testable.
  BEAN_RE='^@(Service|Component|RestController|Controller|Configuration|Repository)\b'
  NON_COLLAB_RE='^(String|int|long|boolean|double|Integer|Long|Boolean|Path|Duration|Instant|Pattern|Clock|ObjectMapper|TransactionTemplate|SimpMessagingTemplate|MeterRegistry|Logger|ExecutorService|ScheduledExecutorService|[A-Za-z]*Supplier|UnaryOperator|Deque|Concurrent[A-Za-z]*|Atomic[A-Za-z]*|[A-Za-z]*Lock|[A-Za-z]*Config|[A-Za-z]*Properties)$'
  ( cd "$SRC" && [ -s "$TMP/java-files.txt" ] \
      && tr '\n' '\0' < "$TMP/java-files.txt" | xargs -0 grep -lE "$BEAN_RE" 2>/dev/null ) \
    | sort > "$TMP/beans.txt"
  if [ -s "$TMP/beans.txt" ]; then
    ( cd "$SRC" && tr '\n' '\0' < "$TMP/beans.txt" | xargs -0 awk -v nonre="$NON_COLLAB_RE" '
    FNR == 1 { if (file != "") print deps "\t" file; file = FILENAME; deps = depth = intext = incomment = 0 }
    {
      if (depth == 1 && !intext && !incomment) {
        s = $0; sub(/[[:space:]]*\/\/.*$/, "", s)
        if (s ~ /^[[:space:]]*private final ([A-Za-z_][A-Za-z0-9_.]*\.)?[A-Za-z_][A-Za-z0-9_]*(<.*>)?(\[\])?[[:space:]]+[A-Za-z_][A-Za-z0-9_]*[[:space:]]*;[[:space:]]*$/) {
          t = s; sub(/^[[:space:]]*private final /, "", t); sub(/[[:space:]].*$/, "", t); sub(/[<[].*$/, "", t); sub(/^.*\./, "", t)
          if (t !~ nonre) deps++
        }
      }
      line = $0; n = length(line); i = 1
      while (i <= n) {
        if (intext) {
          if (!match(substr(line, i), /[\\"]/)) break
          i += RSTART - 1
          if (substr(line, i, 1) == "\\") i += 2
          else if (substr(line, i, 3) == "\"\"\"") { intext = 0; i += 3 }
          else i++
        } else if (incomment) {
          at = index(substr(line, i), "*/")
          if (!at) break
          incomment = 0; i += at + 1
        } else {
          if (!match(substr(line, i), /[{}"'\''\/]/)) break
          i += RSTART - 1; c = substr(line, i, 1)
          if (c == "{") { depth++; i++ }
          else if (c == "}") { depth--; i++ }
          else if (c == "/") {
            if (substr(line, i + 1, 1) == "/") break
            if (substr(line, i + 1, 1) == "*") { incomment = 1; i += 2 } else i++
          } else if (substr(line, i, 3) == "\"\"\"") { intext = 1; i += 3 }
          else {
            for (i++; i <= n && substr(line, i, 1) != c; i++) if (substr(line, i, 1) == "\\") i++
            i++
          }
        }
      }
    }
    END { if (file != "") print deps "\t" file }' ) > "$TMP/deps.tsv" 2>/dev/null || note_error "collaborators_failed"
  fi
fi
# Merge: ncloc, lines, changes (90 days), deps ("-" = no Spring bean), file.
awk -F'\t' 'FILENAME == ARGV[1] { deps[$2] = $1; next }
            FILENAME == ARGV[2] { churn[$2] = $1; next }
            { d = ($3 in deps) ? deps[$3] : "-"; c = ($3 in churn) ? churn[$3] : 0
              print $1 "\t" $2 "\t" c "\t" d "\t" $3 }' \
    "$TMP/deps.tsv" "$TMP/churn.tsv" "$TMP/ncloc.tsv" \
  | sort -t "$(printf '\t')" -k1,1rn -k5,5 > "$TMP/files.tsv"

# ---------------------------------------------------------------------------
# Limits of the architecture tests: SourceSizeRatchetTest.ALLOWLIST (upper bound per file),
# PackageCycleRatchetTest.ALLOWED_PAIRS and .CYCLE_COMPONENTS. The grading then downgrades
# known, test-monitored cycles and names ratchet upper bounds in the evidence.
# ---------------------------------------------------------------------------
ARCH_TESTS="$BACKEND/src/test/java/com/automation/architecture"
python3 - "$ARCH_TESTS" > "$TMP/ratchet.tsv" 2>/dev/null <<'PY' || printf 'state\tfailed\n' > "$TMP/ratchet.tsv"
import os, re, sys
base = sys.argv[1]
def read(name):
    path = os.path.join(base, name)
    return open(path, encoding='utf-8').read() if os.path.isfile(path) else None
size, cycles = read('SourceSizeRatchetTest.java'), read('PackageCycleRatchetTest.java')
if size is None or cycles is None:
    print('state\tmissing')
    sys.exit(0)
limit = re.search(r'MAX_LINES\s*=\s*(\d+)\s*;', size)
pinned = re.search(r'CYCLE_COMPONENTS\s*=\s*List\.of\((.*?)\);', cycles, re.DOTALL)
pairs = re.search(r'ALLOWED_PAIRS\s*=\s*Set\.of\((.*?)\);', cycles, re.DOTALL)
if not limit or not pinned or not pairs:
    print('state\tunparsed')
    sys.exit(0)
print('state\tok')
print('max_lines\t%s' % limit.group(1))
for path, ceiling in sorted(re.findall(r'entry\("([^"]+)",\s*(\d+)\)', size)):
    print('allowlist\t%s\t%s' % (ceiling, path))
allowed = sorted(tuple(sorted(p)) for p in re.findall(r'"([^"\s]+)\s*<->\s*([^"\s]+)"', pairs.group(1)))
for a, b in allowed:
    print('pair\t%s\t%s' % (a, b))
if not allowed:
    # Leere Allowlist ausdruecklich nennen, sonst sieht sie aus wie ein Muster, das nicht mehr greift.
    print('# pair\t(keine)')
for members in (sorted(re.findall(r'"([^"]+)"', group)) for group in re.findall(r'Set\.of\(([^)]*)\)', pinned.group(1))):
    print('component\t%d\t%s' % (len(members), ','.join(members)))
PY
RATCHET_STATE=$(awk -F'\t' '$1 == "state" { print $2; exit }' "$TMP/ratchet.tsv")

# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------
printf '## packages\n'
printf '# Paketabhaengigkeiten aus jdeps -verbose:class, nur com.automation.*, ohne Selbstkanten\n'
printf '# von\tnach\tanzahl (aggregierte Klassenkanten)\n'
if [ -s "$TMP/edges.tsv" ]; then
  cat "$TMP/edges.tsv"
elif [ "$JDEPS_STATE" = "empty" ]; then
  printf '# keine com.automation-Kanten gefunden\n'
else
  printf '# nicht gemessen — siehe error= in ## summary und ## run\n'
fi

printf '\n## cycles\n'
printf '# cycle\tgroesse\tpakete   |   pair\ta\tb (wechselseitige Abhaengigkeit)\n'
if [ -s "$TMP/cycles.tsv" ]; then
  cat "$TMP/cycles.tsv"
elif [ "$CYCLES_STATE" = "ok" ]; then
  printf '# keine Zyklen zwischen com.automation-Paketen gefunden\n'
else
  printf '# nicht gemessen (cycles_state=%s) — kein Beleg fuer Zyklenfreiheit\n' "$CYCLES_STATE"
fi

printf '\n## sizes\n'
printf '# Zeilen je Datei unter ai-automation-backend/src/main/java, Top 30 absteigend\n'
printf '# Zaehlweise wie SourceSizeRatchetTest (MAX_LINES=300)\n'
printf '# zeilen\tdatei\n'
head -30 "$TMP/sizes.tsv"
printf 'files=%s\nfiles_over_300=%s\nfiles_over_500=%s\n' "$FILES" "$FILES_OVER_300" "$FILES_OVER_500"

printf '\n## churn\n'
printf '# geaenderte Java-Dateien der letzten 90 Tage (git log --since=90.days), Top 30\n'
printf '# aenderungen\tdatei\n'
if [ -s "$TMP/churn.tsv" ]; then
  head -30 "$TMP/churn.tsv"
else
  printf '# keine Aenderungen gemessen\n'
fi

printf '\n## recent\n'
printf '# geaenderte Java-Dateien der letzten 7 Tage (git log --since=7.days); neu = im Fenster angelegt\n'
printf '# (--diff-filter=A). Vollstaendig fuer arch-findings.py; der Workflow reicht den Abschnitt nicht an die KI.\n'
printf '# aenderungen\tneu\tdatei\n'
if [ -s "$TMP/recent.tsv" ]; then
  cat "$TMP/recent.tsv"
else
  printf '# keine Aenderungen gemessen\n'
fi

printf '\n## coupling\n'
printf '# je Paket: ausgehende/eingehende Pakete und Klassenkanten, Top 15 nach ausgehend\n'
printf '# paket\tausgehend\teingehend\tausgehende_kanten\teingehende_kanten\n'
if [ -s "$TMP/edges.tsv" ]; then
  awk -F'\t' '{ out_pkg[$1]++; out_w[$1]+=$3; in_pkg[$2]++; in_w[$2]+=$3; seen[$1]=1; seen[$2]=1 }
              END { for (p in seen) printf "%s\t%d\t%d\t%d\t%d\n", p, out_pkg[p]+0, in_pkg[p]+0, out_w[p]+0, in_w[p]+0 }' \
      "$TMP/edges.tsv" | sort -k2,2rn -k4,4rn -k1,1 | head -15
else
  printf '# keine Kopplung gemessen\n'
fi

printf '\n## files\n'
printf '# je Java-Datei: NCLOC (ohne Leer-, Kommentar- und Importzeilen), Zeilen wie oben, Aenderungen\n'
printf '# der letzten 90 Tage, Kollaborateure (injizierte Beans; "-" = keine Spring-Bean), absteigend\n'
printf '# nach NCLOC. Vollstaendig fuer arch-findings.py; der Workflow reicht den Abschnitt nicht an die KI.\n'
printf '# ncloc\tzeilen\taenderungen\tdeps\tdatei\n'
if [ -s "$TMP/files.tsv" ]; then
  cat "$TMP/files.tsv"
else
  printf '# nicht gemessen — siehe error= in ## summary\n'
fi

printf '\n## ratchet\n'
printf '# Grenzen der Architektur-Tests unter ai-automation-backend/src/test/java/com/automation/architecture\n'
printf '# state\tok|missing|unparsed|failed   max_lines\tSourceSizeRatchetTest.MAX_LINES\n'
printf '# allowlist\tobergrenze\tdatei (SourceSizeRatchetTest.ALLOWLIST)\n'
printf '# pair\ta\tb (PackageCycleRatchetTest.ALLOWED_PAIRS)   component\tgroesse\tpakete (.CYCLE_COMPONENTS)\n'
cat "$TMP/ratchet.tsv"

PACKAGES=0
if [ -d "$CLASSES/com/automation" ]; then
  PACKAGES=$(find "$CLASSES/com/automation" -type f -name '*.class' | sed 's#/[^/]*$##' | sort -u | wc -l | tr -d ' ')
fi
EDGES=$(wc -l < "$TMP/edges.tsv" | tr -d ' ')
# grep -c prints "0" on 0 hits and exits with 1 — no "|| echo 0", that would yield two lines.
CYCLES=$(grep -c '^cycle' "$TMP/cycles.tsv" 2>/dev/null); CYCLES=${CYCLES:-0}
PAIRS=$(grep -c '^pair' "$TMP/cycles.tsv" 2>/dev/null); PAIRS=${PAIRS:-0}
MAX_LINES=$(head -1 "$TMP/sizes.tsv" 2>/dev/null | cut -f1)

printf '\n## summary\n'
printf 'packages=%s\n' "$PACKAGES"
printf 'edges=%s\n' "$EDGES"
printf 'cycles=%s\n' "$CYCLES"
printf 'cycle_pairs=%s\n' "$PAIRS"
printf 'cycles_state=%s\n' "$CYCLES_STATE"
printf 'files=%s\n' "$FILES"
printf 'files_over_300=%s\n' "$FILES_OVER_300"
printf 'files_over_500=%s\n' "$FILES_OVER_500"
printf 'max_file_lines=%s\n' "${MAX_LINES:-0}"
printf 'churn_files_90d=%s\n' "$CHURN_FILES"
printf 'ncloc_total=%s\n' "$(awk -F'\t' '{ s += $1 } END { print s + 0 }' "$TMP/files.tsv")"
printf 'beans=%s\n' "$(wc -l < "$TMP/deps.tsv" | tr -d ' ')"
printf 'ratchet_state=%s\n' "${RATCHET_STATE:-failed}"
printf 'commit=%s\n' "$COMMIT"
[ -n "$ERRORS" ] && printf 'error=%s\n' "$ERRORS"

# ## run is deliberately at the end and outside the determinism comparison: compile= depends
# on whether the working directory (or the cache) already had a build, not on the
# measured state.
printf '\n## run\n'
printf '# Laufzeit-Zustand, nicht Teil des Determinismus-Vergleichs\n'
printf 'compile=%s\n' "$COMPILE_STATE"
printf 'jdeps=%s\n' "$JDEPS_STATE"
if [ -n "${ARCH_METRICS_CLASSES_OUT:-}" ] && [ -d "$CLASSES" ]; then
  printf '%s\n' "$CLASSES" > "$ARCH_METRICS_CLASSES_OUT" 2>/dev/null || true
fi
exit 0
