#!/usr/bin/env bash
#
# arch-metrics.sh — deterministische Architektur-Metriken fuer das Architektur-Review.
#
# Misst mit Bordmitteln (JDK/git/coreutils/python3, keine neuen Abhaengigkeiten):
#   ## packages  Paketabhaengigkeiten aus `jdeps -verbose:class` (nur com.automation.*)
#   ## cycles    Zyklen zwischen Paketen (Tarjan in arch_cycles.py) — dieselben Paar-Kandidaten
#                stehen zusaetzlich in der Allowlist von PackageCycleRatchetTest.ALLOWED_PAIRS
#                (ai-automation-backend/src/test/java/com/automation/architecture/). Wer ein
#                Paket umbenennt oder verschiebt, muss diese Allowlist im selben Commit mitziehen —
#                sonst schlaegt der Ratchet beim naechsten vollen Testlauf fehl, obwohl sich am
#                tatsaechlichen Zyklus nichts geaendert hat.
#   ## sizes     Zeilen je Java-Datei, Top 30, plus Schwellenzaehler (300/500)
#   ## churn     geaenderte Dateien der letzten 90 Tage aus git log
#   ## coupling  ein-/ausgehende Paketabhaengigkeiten, Top 15 nach ausgehend
#   ## summary   Kennzahlen als key=value
#   ## run       Laufzeit-Zustand (compile=reused|cached|compiled|…, jdeps=) — bewusst NICHT
#                deterministisch
#
# Die Ausgabe ist ein Faktenblatt fuer einen KI-Block, der nur interpretiert — sie ist
# stabil sortiert und enthaelt keine Zeitstempel.
#
# Determinismus, genau: alles vor `## run` ist zwischen zwei Laeufen byte-identisch,
# solange (a) HEAD derselbe ist, (b) beide Laeufe im selben 90-Tage-Churn-Fenster liegen
# (also kein Commit ueber die Grenze rutscht) und (c) das gemessene Kompilat zum selben
# Quellstand gehoert. Der Laufzeit-Zustand steht deshalb unter `## run`: `compile=` wechselt
# beim ersten Lauf in einem frischen Arbeitsverzeichnis von `compiled` auf `reused` bzw.
# `cached`, das ist kein Messunterschied und darf keinen Determinismus-Vergleich roetlich
# faerben.
#
# Usage:  scripts/public-readiness/arch-metrics.sh [repo-root]        (Default: .)
# Env:    ARCH_METRICS_COMPILE=0   nie kompilieren, nur vorhandenes target/classes nutzen
#         AUTOMATION_ARCH_CACHE    Kompilat-Cache (Default: ~/.cache/mysuricates/arch-classes)
# Exit:   0 = Faktenblatt geschrieben (auch mit error=-Markierungen), 2 = Wurzel unbrauchbar
#
# Zeitbudget: fehlt ai-automation-backend/target/classes, laeuft einmalig
# `./mvnw -q -DskipTests compile` (~1-2 Minuten auf dem Dev-Host); jdeps und die Zaehler
# brauchen danach ~10-20 Sekunden. Im Workflow deshalb timeout_seconds: 600.
#
# Kompilat-Cache: ein Workspace-Klon (isolierter Run) hat nie ein target/classes und wuerde
# jedes Mal neu kompilieren. Liegt unter $AUTOMATION_ARCH_CACHE/<commit-sha>/ schon ein
# Kompilat des gemessenen Commits, wird direkt daraus gemessen (compile=cached); sonst wird
# kompiliert und das Ergebnis dort abgelegt (compile=compiled). Schluessel ist `git rev-parse
# HEAD`, und nur bei sauberem Arbeitsbaum — bei uncommitteten Aenderungen waere ein Treffer
# eine falsche Messung. Es bleiben die juengsten drei Eintraege liegen. Jeder Fehlschlag am
# Cache ist still: gemessen wird dann eben frisch.
#
# Parallele Laeufe: ein benutzter Eintrag wird beim Treffer frisch gestempelt (mtime), damit
# das Aufraeumen ihn nicht mitten in der jdeps-Messung wegzieht, und ein bereits gueltiger
# Eintrag wird nie ersetzt. Zwei Laeufe desselben Commits kommen sich damit nicht in die
# Quere; schlimmstenfalls verwirft einer sein frisch kopiertes Staging.
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

# ---------------------------------------------------------------------------
# Klassen bereitstellen (jdeps braucht Bytecode, kein Quelltext)
# ---------------------------------------------------------------------------
CACHE_ROOT="${AUTOMATION_ARCH_CACHE:-${HOME:-$TMP}/.cache/mysuricates/arch-classes}"
CACHE_KEEP=3

# Cache-Schluessel ist der gemessene Commit — und nur, wenn der Arbeitsstand ihm entspricht.
# Bei uncommitteten Aenderungen waere ein Treffer eine falsche Messung: gemessen wuerde der
# Commit, gemeldet der Arbeitsstand. Kein Repo, kein HEAD, schmutziger Baum ⇒ kein Cache.
cache_key() {
  git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1 || return 1
  local sha
  sha=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null) || return 1
  [ -n "$sha" ] || return 1
  [ -z "$(git -C "$ROOT" status --porcelain 2>/dev/null)" ] || return 1
  printf '%s' "$sha"
}

has_classes() { [ -n "$(find "$1" -name '*.class' -print -quit 2>/dev/null)" ]; }

# Nur die juengsten CACHE_KEEP Eintraege bleiben liegen; ein Kompilat je Commit waechst sonst
# ueber die Monate zu einem stillen Gigabyte im Home-Verzeichnis heran. Punkt-Verzeichnisse
# (Staging eines parallelen Laufs) zaehlen nicht mit und werden nicht angetastet.
prune_cache() {
  find "$CACHE_ROOT" -mindepth 1 -maxdepth 1 -type d ! -name '.*' -printf '%T@\t%p\n' 2>/dev/null \
    | sort -rn \
    | awk -v keep="$CACHE_KEEP" -F'\t' 'NR > keep { print $2 }' \
    | while IFS= read -r stale; do
        case "$stale" in "$CACHE_ROOT"/?*) rm -rf "$stale" ;; esac
      done
}

# Ein Treffer wird benutzt, also frisch gestempelt: jdeps liest 10-20 s daraus, und der
# Keep-3-Rang geht nach mtime — ohne das koennte ein paralleler Lauf den Eintrag genau
# waehrend der Messung wegraeumen und wir wuerden einen halben Klassenbaum messen.
touch_cache() { touch "$1" 2>/dev/null || true; }

# Ablegen ist Kuer: jeder Fehlschlag (Rechte, volle Platte, unbrauchbarer Pfad) bleibt still,
# das Faktenblatt ist davon unberuehrt.
store_cache() {  # $1 = Schluessel, $2 = Quellverzeichnis
  [ -n "$1" ] || return 0
  has_classes "$2" || return 0
  local entry="$CACHE_ROOT/$1"
  # Ein gueltiger Eintrag wird nie ersetzt — ein Treffer ist ein Treffer, und ein paralleler
  # Lauf koennte gerade daraus messen. Nur ein halb geschriebener Rest wird aufgeraeumt.
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
    # Direkt aus dem Cache messen statt zurueckzukopieren: jdeps liest nur, und target/classes
    # bleibt so das, was ein echter Build dort hinterlaesst. Vorher stempeln, damit der
    # Eintrag waehrend der Messung nicht von einem parallelen Lauf weggeraeumt wird.
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
# Paketkanten aus jdeps: Klassenkanten werden auf Paketebene aggregiert,
# Selbstkanten (Paket auf sich selbst) fallen weg.
# ---------------------------------------------------------------------------
JDEPS_STATE="ok"
if ! command -v jdeps >/dev/null 2>&1; then
  JDEPS_STATE="missing"
  note_error "jdeps_missing"
elif [ -d "$CLASSES" ]; then
  # Erst roh einsammeln, dann auswerten: in einer Pipe ginge der Rueckgabewert von jdeps
  # verloren und ein Fehlschlag saehe aus wie "keine Abhaengigkeiten".
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

# Zyklen: ein Fehlschlag der Erkennung darf nicht als "keine Zyklen" durchgehen — das waere
# falsch-gruen in genau der Dimension, fuer die das Faktenblatt gebaut wurde.
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
# Dateigroessen — gleiche Zaehlweise wie SourceSizeRatchetTest
# (Files.readAllLines(..).size(): awk zaehlt eine letzte Zeile ohne Zeilenumbruch mit).
# Leere Dateien liefern awk keinen Satz und wuerden fehlen — sie kommen per find -empty
# mit 0 Zeilen dazu, damit die Zeilenzahl der Liste zu files= passt.
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
printf 'cycles_state=%s\n' "$CYCLES_STATE"
printf 'files=%s\n' "$FILES"
printf 'files_over_300=%s\n' "$FILES_OVER_300"
printf 'files_over_500=%s\n' "$FILES_OVER_500"
printf 'max_file_lines=%s\n' "${MAX_LINES:-0}"
printf 'churn_files_90d=%s\n' "$CHURN_FILES"
printf 'commit=%s\n' "$COMMIT"
[ -n "$ERRORS" ] && printf 'error=%s\n' "$ERRORS"

# ## run steht bewusst am Ende und ausserhalb des Determinismus-Vergleichs: compile= haengt
# davon ab, ob das Arbeitsverzeichnis (oder der Cache) schon ein Kompilat hatte, nicht vom
# gemessenen Stand.
printf '\n## run\n'
printf '# Laufzeit-Zustand, nicht Teil des Determinismus-Vergleichs\n'
printf 'compile=%s\n' "$COMPILE_STATE"
printf 'jdeps=%s\n' "$JDEPS_STATE"
exit 0
