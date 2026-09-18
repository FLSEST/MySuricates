#!/usr/bin/env bash
#
# arch-metrics.sh — deterministische Architektur-Metriken fuer das Architektur-Review.
#
# Misst mit Bordmitteln (JDK/git/coreutils/python3, keine neuen Abhaengigkeiten):
#   ## packages  Paketabhaengigkeiten aus `jdeps -verbose:class` (nur com.automation.*)
#   ## cycles    Zyklen zwischen Paketen (Tarjan in arch_cycles.py)
#   ## sizes     Zeilen je Java-Datei, Top 30, plus Schwellenzaehler (300/500)
#   ## churn     geaenderte Dateien der letzten 90 Tage aus git log
#   ## coupling  ein-/ausgehende Paketabhaengigkeiten, Top 15 nach ausgehend
#   ## summary   Kennzahlen als key=value
#
# Die Ausgabe ist ein Faktenblatt fuer einen KI-Block, der nur interpretiert — sie ist
# stabil sortiert und enthaelt keine Zeitstempel, damit zwei Laeufe identisch sind.
#
# Usage:  scripts/public-readiness/arch-metrics.sh [repo-root]        (Default: .)
# Env:    ARCH_METRICS_COMPILE=0   nie kompilieren, nur vorhandenes target/classes nutzen
# Exit:   0 = Faktenblatt geschrieben (auch mit error=-Markierungen), 2 = Wurzel unbrauchbar
#
# Zeitbudget: fehlt ai-automation-backend/target/classes, laeuft einmalig
# `./mvnw -q -DskipTests compile` (~1-2 Minuten auf dem Dev-Host); jdeps und die Zaehler
# brauchen danach ~10-20 Sekunden. Im Workflow deshalb timeout_seconds: 600.
#
# Bewusst ohne common.sh: dessen run_limited/MAX_LINES wuerde das Faktenblatt kuerzen und
# cd_root die uebergebene Wurzel ignorieren.
#
set -uo pipefail
export LC_ALL=C

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
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

ERRORS=""
note_error() { ERRORS="${ERRORS:+$ERRORS,}$1"; }

: > "$TMP/edges.tsv"
: > "$TMP/cycles.tsv"
: > "$TMP/sizes.tsv"
: > "$TMP/churn.tsv"

# ---------------------------------------------------------------------------
# Klassen bereitstellen (jdeps braucht Bytecode, kein Quelltext)
# ---------------------------------------------------------------------------
COMPILE_STATE="reused"
if [ ! -d "$CLASSES" ]; then
  if [ "${ARCH_METRICS_COMPILE:-1}" != "1" ]; then
    COMPILE_STATE="skipped"
  elif [ -x "$BACKEND/mvnw" ] && [ -f "$BACKEND/pom.xml" ]; then
    echo "arch-metrics: target/classes fehlt — kompiliere (Zeitbudget ca. 1-2 Minuten)" >&2
    if ( cd "$BACKEND" && ./mvnw -q -DskipTests compile ) >&2; then
      COMPILE_STATE="compiled"
    else
      COMPILE_STATE="failed"
    fi
  else
    COMPILE_STATE="unavailable"
  fi
fi
[ -d "$CLASSES" ] || note_error "classes_missing"

# ---------------------------------------------------------------------------
# Paketkanten aus jdeps: Klassenkanten werden auf Paketebene aggregiert,
# Selbstkanten (Paket auf sich selbst) fallen weg.
# ---------------------------------------------------------------------------
JDEPS_STATE="ok"
if ! command -v jdeps >/dev/null 2>&1; then
  JDEPS_STATE="missing"
  note_error "jdeps_missing"
elif [ -d "$CLASSES" ]; then
  jdeps -verbose:class -filter:none "$CLASSES" 2>/dev/null \
    | awk 'NF==4 && $2=="->" && $1 ~ /^com\.automation\./ && $3 ~ /^com\.automation\./ {
             from=$1; to=$3;
             sub(/\.[^.]*$/, "", from); sub(/\.[^.]*$/, "", to);
             if (from != to) print from "\t" to
           }' \
    | sort | uniq -c \
    | awk '{ print $2 "\t" $3 "\t" $1 }' \
    | sort -k1,1 -k2,2 > "$TMP/edges.tsv"
  [ -s "$TMP/edges.tsv" ] || JDEPS_STATE="empty"
else
  JDEPS_STATE="no_classes"
fi

if [ -s "$TMP/edges.tsv" ]; then
  cut -f1,2 "$TMP/edges.tsv" | python3 "$SCRIPT_DIR/arch_cycles.py" > "$TMP/cycles.tsv" 2>/dev/null
fi

# ---------------------------------------------------------------------------
# Dateigroessen — gleiche Zaehlweise wie SourceSizeRatchetTest
# (Files.readAllLines(..).size(): awk zaehlt eine letzte Zeile ohne Zeilenumbruch mit).
# ---------------------------------------------------------------------------
FILES=0
if [ -d "$SRC" ]; then
  ( cd "$SRC" && find . -type f -name '*.java' -print0 \
      | xargs -0 -r awk 'FNR==1 { if (f != "") { sub(/^\.\//, "", f); print n "\t" f } f=FILENAME } { n=FNR }
                         END { if (f != "") { sub(/^\.\//, "", f); print n "\t" f } }' ) \
    | sort -k1,1rn -k2,2 > "$TMP/sizes.tsv"
  FILES=$(find "$SRC" -type f -name '*.java' | wc -l | tr -d ' ')
else
  note_error "source_missing"
fi
FILES_OVER_300=$(awk -F'\t' '$1 > 300' "$TMP/sizes.tsv" | wc -l | tr -d ' ')
FILES_OVER_500=$(awk -F'\t' '$1 > 500' "$TMP/sizes.tsv" | wc -l | tr -d ' ')

# ---------------------------------------------------------------------------
# Git-Churn der letzten 90 Tage
# ---------------------------------------------------------------------------
COMMIT="unknown"
if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  COMMIT=$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)
  git -C "$ROOT" log --since=90.days --name-only --format= -- ai-automation-backend/src/main/java 2>/dev/null \
    | grep -v '^$' \
    | sed 's#^ai-automation-backend/src/main/java/##' \
    | sort | uniq -c \
    | awk '{ print $1 "\t" $2 }' \
    | sort -k1,1rn -k2,2 > "$TMP/churn.tsv"
else
  note_error "no_git_repo"
fi
CHURN_FILES=$(wc -l < "$TMP/churn.tsv" | tr -d ' ')

# ---------------------------------------------------------------------------
# Ausgabe
# ---------------------------------------------------------------------------
printf '## packages\n'
printf '# Paketabhaengigkeiten aus jdeps -verbose:class, nur com.automation.*, ohne Selbstkanten\n'
printf '# von\tnach\tanzahl (aggregierte Klassenkanten)\n'
if [ -s "$TMP/edges.tsv" ]; then
  cat "$TMP/edges.tsv"
else
  printf '# keine Kanten gemessen (jdeps=%s, compile=%s)\n' "$JDEPS_STATE" "$COMPILE_STATE"
fi

printf '\n## cycles\n'
printf '# cycle\tgroesse\tpakete   |   pair\ta\tb (wechselseitige Abhaengigkeit)\n'
if [ -s "$TMP/cycles.tsv" ]; then
  cat "$TMP/cycles.tsv"
else
  printf '# keine Zyklen zwischen com.automation-Paketen gefunden\n'
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

PACKAGES=0
if [ -d "$CLASSES/com/automation" ]; then
  PACKAGES=$(find "$CLASSES/com/automation" -type f -name '*.class' | sed 's#/[^/]*$##' | sort -u | wc -l | tr -d ' ')
fi
EDGES=$(wc -l < "$TMP/edges.tsv" | tr -d ' ')
# grep -c schreibt bei 0 Treffern "0" und endet mit 1 — kein "|| echo 0", das ergaebe zwei Zeilen.
CYCLES=$(grep -c '^cycle' "$TMP/cycles.tsv" 2>/dev/null); CYCLES=${CYCLES:-0}
PAIRS=$(grep -c '^pair' "$TMP/cycles.tsv" 2>/dev/null); PAIRS=${PAIRS:-0}
MAX_LINES=$(head -1 "$TMP/sizes.tsv" 2>/dev/null | cut -f1)

printf '\n## summary\n'
printf 'packages=%s\n' "$PACKAGES"
printf 'edges=%s\n' "$EDGES"
printf 'cycles=%s\n' "$CYCLES"
printf 'cycle_pairs=%s\n' "$PAIRS"
printf 'files=%s\n' "$FILES"
printf 'files_over_300=%s\n' "$FILES_OVER_300"
printf 'files_over_500=%s\n' "$FILES_OVER_500"
printf 'max_file_lines=%s\n' "${MAX_LINES:-0}"
printf 'churn_files_90d=%s\n' "$CHURN_FILES"
printf 'commit=%s\n' "$COMMIT"
printf 'compile=%s\n' "$COMPILE_STATE"
printf 'jdeps=%s\n' "$JDEPS_STATE"
[ -n "$ERRORS" ] && printf 'error=%s\n' "$ERRORS"
exit 0
