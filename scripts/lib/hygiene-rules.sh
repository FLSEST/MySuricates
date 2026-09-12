#!/usr/bin/env bash
#
# Gemeinsame Regelbasis für das Hygiene-Gate.
#
# Wird von scripts/ci/public-hygiene.sh (CI und lokal) und von
# scripts/git-hooks/pre-commit (gestagte Änderungen) gelesen. Vorher standen
# Muster- und Pfadlisten wortgleich in beiden Dateien — eine Ergänzung an einer
# Stelle fehlte damit an der anderen.
#
# Diese Datei definiert nur Daten und kleine Helfer, sie führt nichts aus.
#
# shellcheck shell=bash

# ---------------------------------------------------------------------------
# Verbotene Literale in getrackten Dateien
#
# Format: "muster|beschreibung". Bewusst Literale, keine Regex: sie werden mit
# git grep -F bzw. einem Bash-Substring-Vergleich gesucht, damit Hook und CI
# dieselbe Semantik haben.
# ---------------------------------------------------------------------------
HYGIENE_PATTERNS=(
  "/home/flo|absolute developer path"
  "test-e2e-key|hardcoded test API key string"
  "ebf4f50c-20c2-4c35-a84a-a0b350124523|internal project UUID"
  "changeme|known default credential"
)

# ---------------------------------------------------------------------------
# Dateien, die die Muster legitim als Zeichenketten enthalten
# ---------------------------------------------------------------------------
HYGIENE_SELF_EXCLUDE=(
  "scripts/lib/hygiene-rules.sh"
  "scripts/ci/public-hygiene.sh"
  "scripts/ci/pool-neutrality.sh"
  "scripts/git-hooks/pre-commit"
)

# ---------------------------------------------------------------------------
# Sicherungsdateien, die weder getrackt noch ignoriert sein dürfen
#
# Eine solche Datei ist lokal nur eine Warnung, beim Stagen ein Fehler: genau so
# wäre workflow-pool/job-shortlist-template.html.bak-<zeitstempel> beinahe in
# einen Release-Commit geraten.
# ---------------------------------------------------------------------------
HYGIENE_BACKUP_GLOBS=(
  '*.bak' '*.bak-*' '*.orig' '*.old' '*~' '*.save' '*.rej'
)

# ---------------------------------------------------------------------------
# Repo-Gewicht
# ---------------------------------------------------------------------------
# Ab dieser Größe gilt ein getrackter Blob als erklärungsbedürftig.
HYGIENE_MAX_BLOB_BYTES=${HYGIENE_MAX_BLOB_BYTES:-5242880}   # 5 MiB
# Ab dieser Größe zählt ein doppelt getrackter Blob als Dublette.
HYGIENE_DUP_MIN_BYTES=${HYGIENE_DUP_MIN_BYTES:-262144}      # 256 KiB

# ---------------------------------------------------------------------------
# Allowlist: bewusste Ausnahmen, nur Pfade mit Begründung
#
# Absichtlich ohne Musterliterale — die Allowlist selbst soll nichts preisgeben.
# ---------------------------------------------------------------------------
HYGIENE_ALLOWLIST_FILE="scripts/ci/hygiene-allowlist.txt"

# Liest die Allowlist als Pfadliste (Kommentare und Leerzeilen fallen weg).
hygiene_allowlist_paths() {
  [ -f "$HYGIENE_ALLOWLIST_FILE" ] || return 0
  sed -e 's/#.*//' -e 's/[[:space:]]*$//' "$HYGIENE_ALLOWLIST_FILE" | grep -v '^$' || true
}

# hygiene_is_allowlisted <pfad>
hygiene_is_allowlisted() {
  local p="$1" entry
  while IFS= read -r entry; do
    [ -z "$entry" ] && continue
    [ "$p" = "$entry" ] && return 0
  done < <(hygiene_allowlist_paths)
  return 1
}

# hygiene_is_self_excluded <pfad>
hygiene_is_self_excluded() {
  local p="$1" e
  for e in "${HYGIENE_SELF_EXCLUDE[@]}"; do
    [ "$p" = "$e" ] && return 0
  done
  return 1
}

# hygiene_pattern_of <eintrag> / hygiene_desc_of <eintrag>
hygiene_pattern_of() { echo "${1%%|*}"; }
hygiene_desc_of()    { echo "${1##*|}"; }
