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
  "[auto-generated]|nie ersetzter Stempel-Platzhalter, ersatzlos streichen (siehe TODO 994af971)"
)

# ---------------------------------------------------------------------------
# Dateien, die die Muster legitim als Zeichenketten enthalten
# ---------------------------------------------------------------------------
HYGIENE_SELF_EXCLUDE=(
  "scripts/lib/hygiene-rules.sh"
  "scripts/ci/public-hygiene.sh"
  "scripts/ci/pool-neutrality.sh"
  "scripts/git-hooks/pre-commit"
  "scripts/tests/test-public-hygiene.sh"
)

# ---------------------------------------------------------------------------
# Sicherungsdateien, die weder getrackt noch ignoriert sein dürfen
#
# Eine solche Datei ist lokal nur eine Warnung, beim Stagen ein Fehler: genau so
# wäre workflow-pool/job-shortlist-template.html.bak-<zeitstempel> beinahe in
# einen Release-Commit geraten.
# ---------------------------------------------------------------------------
# Commit-Metadaten (Pre-Push-Gate, scripts/ci/pre-push-checks.sh; TODOs 7030f362, 5e0edb7c):
# Autor und Committer tragen nur die GitHub-noreply-Form, nie eine private Adresse.
# Die Adresse selbst steht nirgends im Repository — nur ihre Form.
HYGIENE_COMMIT_EMAIL_REGEX='@users\.noreply\.github\.com$'
# Trailer, deren Adressen in der Commit-Nachricht erlaubt sind (KI-Urheberschaft, Sign-off).
# Alle anderen Zeilen einer Nachricht duerfen keine Mailadresse enthalten.
HYGIENE_COMMIT_TRAILER_REGEX='^(Co-Authored-By|Co-authored-by|Generated-By|Signed-off-by|Reviewed-by): '
HYGIENE_EMAIL_REGEX='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'

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
# SECURITY.md — Pflichtdatei mit einem echten Meldeweg
#
# Nur im getrackten Bestand geprueft (--tracked/--worktree), nicht bei --staged:
# jeder Commit einzeln zu blockieren waere zu aufdringlich fuer eine Datei, die
# sich selten aendert. Die Release-Frage ist "ist der Zustand am Ende richtig?".
# ---------------------------------------------------------------------------
HYGIENE_SECURITY_FILE="SECURITY.md"
HYGIENE_SECURITY_EMAIL_REGEX='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[a-z]{2,}'
# Mindestens einer dieser Begriffe muss vorkommen (Meldeweg erkennbar).
HYGIENE_SECURITY_REQUIRED_TERMS=(
  "Private vulnerability reporting"
  "Security Advisories"
)

# ---------------------------------------------------------------------------
# Kuratierungsregeln für den öffentlichen Workflow-Pool (TODO 523702f4)
#
# Der Plan steht in docs/workflow-pool-kuratierung.md; die Werte hier sind seine
# maschinell prüfbare Seite. Gelesen von scripts/ci/pool-neutrality.sh.
# ---------------------------------------------------------------------------

# Erlaubte Werte des Pflichtfelds "scope".
#   repo     — greift auf die Struktur dieses Repositories zu
#   portable — über project_path (o. ä.) parametrisiert, läuft in jedem Projekt
HYGIENE_POOL_SCOPES=("repo" "portable")

# Registrierte Blocktypen. Quelle: BlockTypesIndexSection.BLOCK_TYPES im Backend
# (dort selbst gegen die BlockExecutor-Klassen gepinnt, BlockTypeListsGuardTest).
# scripts/tests/test-public-hygiene.sh vergleicht beide Listen — driftet eine,
# fällt es dort auf.
# HYGIENE_POOL_BLOCK_TYPES steht nicht hier, sondern kommt aus der generierten
# Datei daneben (Quelle: BlockTypesIndexSection.BLOCK_TYPES im Backend, erzeugt
# von BlockTypesGateListTest). Vorher war die Liste handgepflegt und wurde erst
# nachtraeglich von Check 13 der Hygiene-Tests verglichen — ein neuer Blocktyp
# fiel also erst auf, wenn jemand diese Tests laufen liess (TODO bdc784e1).
# Eingelesen wird sie unten zusammen mit pool-sanitizer.generated.sh.

# Kennzeichen, an denen ein Eintrag an DIESES Repository gebunden ist. In einem
# Eintrag mit scope: portable sind sie ein Befund — wer ihn in sein eigenes
# Projekt installiert, läuft sonst ins Leere.
HYGIENE_POOL_REPO_MARKERS=(
  "ai-automation-backend" "ai-automation-frontend" "scripts/" "mvnw" "workflow-pool/"
)

# Schlüssel, die eine laufende Instanz bezeichnen. Als Feld der Definition, im
# defaultContext oder in den Metadaten sind sie ein Befund — es sei denn, der
# Wert ist leer oder eine Template-Variable ({{ project_id }}), denn das ist
# gerade die portable Parametrisierung.
#
# HYGIENE_POOL_INSTANCE_KEYS steht nicht hier, sondern kommt aus der generierten
# Datei daneben: Quelle ist PoolExportSanitizer im Backend, das dieselben
# Schlüssel beim Export entfernt. Vorher waren es zwei Listen ohne Kopplung —
# driftete eine, merkte es niemand (TODO 96640e6f).
HYGIENE_RULES_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$HYGIENE_RULES_DIR/pool-sanitizer.generated.sh" ]; then
  # shellcheck source=pool-sanitizer.generated.sh
  . "$HYGIENE_RULES_DIR/pool-sanitizer.generated.sh"
else
  echo "FEHLER: scripts/lib/pool-sanitizer.generated.sh fehlt (erzeugt von PoolSanitizerKeysTest)." >&2
  return 2 2>/dev/null || exit 2
fi

if [ -f "$HYGIENE_RULES_DIR/block-types.generated.sh" ]; then
  # shellcheck source=block-types.generated.sh
  . "$HYGIENE_RULES_DIR/block-types.generated.sh"
else
  echo "FEHLER: scripts/lib/block-types.generated.sh fehlt (erzeugt von BlockTypesGateListTest)." >&2
  return 2 2>/dev/null || exit 2
fi

# ---------------------------------------------------------------------------
# Kriterium „Definition ruft die eigene REST-API oder liest Zugangsdaten"
# (TODO 1ccdc6d5)
#
# Die Hausregel lautet: Workflow-Blöcke rufen weder die eigene REST-API noch die
# Datenbank auf — alles, was sie brauchen, injiziert der WorkflowExecutor als
# Kontextvariable ({{ open_todos }}, {{ previous_report }}, …). In einer
# mitveröffentlichten Definition kommt ein zweites Problem dazu: ein Prompt, der
# erklärt, wie man sich den API-Key aus app_settings holt, ist eine Anleitung
# zum Auslesen von Zugangsdaten — jeder Leser installiert sie mit.
# ---------------------------------------------------------------------------

# Zugangsdaten: keine Ausnahme, das Vorkommen allein ist der Befund.
HYGIENE_POOL_CREDENTIAL_MARKERS=("X-API-Key" "app_settings")

# Eigene REST-API. Das Präfix allein genügt nicht als Befund: mocking-gap-audit
# führt "/api/v1/" als Suchmuster in einer Liste von Netzwerk-Kennzeichen, und
# das ist kein Aufruf. Gemeldet wird erst die Kombination aus Pfad und Aufruf.
HYGIENE_POOL_API_PREFIX="/api/v1/"
HYGIENE_POOL_API_CALLERS=("curl" "wget" "requests." "urllib" "fetch(" "http.client")

# Ohne API-Key erreichbar (ApiKeyAuthFilter) und deshalb als Lebenszeichen-Sonde
# erlaubt — mysuricates-performance-audit misst damit die Antwortzeit und liest aus
# /api/v1/system/jvm-memory die drei Heap-Zahlen (c2cda5ed; /actuator/metrics braucht den Key).
HYGIENE_POOL_API_ALLOWED=(
  "/api/v1/system/health" "/api/v1/system/info" "/api/v1/system/auth-status"
  "/api/v1/system/jvm-memory"
)

# Direkter Datenbankzugriff. Die Datei anzusehen (stat, du) bleibt erlaubt —
# gemeint ist das Lesen des Inhalts, das an Hibernate und pool-size=1 vorbeigeht.
HYGIENE_POOL_DB_FILES=("automation.db")
HYGIENE_POOL_DB_ACCESS=("sqlite3" "SELECT " "INSERT INTO" "UPDATE " "DELETE FROM" ".tables" ".schema")

# ---------------------------------------------------------------------------
# Generische Build-Namen: in .git/info/exclude UND .gitignore erlaubt
#
# check_gitignore_leaks (public-hygiene.sh) meldet sonst jeden Namen aus der
# lokalen exclude-Datei, der auch in .gitignore auftaucht, als Leck. Diese
# Namen sind zu generisch, um etwas Persoenliches zu verraten — jedes
# Node/Java/Python-Projekt erzeugt sie. Die Regel bleibt fuer alles andere in
# Kraft, insbesondere workflow-pool/* (siehe TODO 819b387e).
# ---------------------------------------------------------------------------
HYGIENE_GITIGNORE_LEAK_ALLOWED_NAMES=(
  "node_modules" "target" "dist" "build" ".venv" "__pycache__" "*.log"
)

# hygiene_gitignore_leak_is_generic <basisname>
hygiene_gitignore_leak_is_generic() {
  local base="$1" n
  for n in "${HYGIENE_GITIGNORE_LEAK_ALLOWED_NAMES[@]}"; do
    [ "$base" = "$n" ] && return 0
  done
  return 1
}

# ---------------------------------------------------------------------------
# Allowlist: bewusste Ausnahmen, nur Pfade mit Begründung
#
# Absichtlich ohne Musterliterale — die Allowlist selbst soll nichts preisgeben.
# ---------------------------------------------------------------------------
HYGIENE_ALLOWLIST_FILE="scripts/ci/hygiene-allowlist.txt"

# ---------------------------------------------------------------------------
# Private Ausschlussliste für den Workflow-Pool (TODO 0640f29b)
#
# Die persönlichen Pool-Definitionen bleiben über .git/info/exclude aus dem
# Bestand — dort steht keine Klartext-Namensliste im öffentlichen Repository.
# exclude wird aber nicht geklont und gilt in keinem Worktree: ein pauschales
# "git add -A" holt die Dateien zurück, ohne dass etwas anschlägt. Diese Liste
# ist die durchsetzbare Hälfte davon; sie liegt selbst nicht im Repository
# (auch sie steht in .git/info/exclude). Fehlt sie, wird der Check hörbar
# übersprungen statt still zu bestehen. Vorlage: die .example-Datei daneben.
#
# Weil sie untrackt ist, fehlt sie in jedem Klon und jedem Worktree — auch im
# isolierten Run-Workspace, in dem der Evidenz-Workflow das Gate fährt (TODO
# 6e601c77, Private Hygiene-Liste fehlt). Deshalb gibt es zwei Ausweichorte
# ausserhalb des Repositorys: $HYGIENE_PRIVATE_LIST und die Nutzerkonfiguration.
# ---------------------------------------------------------------------------
HYGIENE_PRIVATE_LIST_FILE="scripts/ci/hygiene-private.txt"

# hygiene_private_list_path <repo-root> — gibt den Pfad der ersten vorhandenen
# Liste aus (Repo-Datei, dann $HYGIENE_PRIVATE_LIST, dann
# ${XDG_CONFIG_HOME:-$HOME/.config}/mysuricates/hygiene-private.txt); Exit 1,
# wenn keine da ist.
hygiene_private_list_path() {
  local root="$1" c
  for c in "$root/$HYGIENE_PRIVATE_LIST_FILE" \
           "${HYGIENE_PRIVATE_LIST:-}" \
           "${XDG_CONFIG_HOME:-${HOME:-}/.config}/mysuricates/hygiene-private.txt"; do
    [ -n "$c" ] && [ -f "$c" ] && { printf '%s\n' "$c"; return 0; }
  done
  return 1
}

# ---------------------------------------------------------------------------
# Private Begriffsliste (TODO 1b84ecab)
#
# Die verbotenen Literale oben stehen im Klartext im Repository — fuer Namen
# privater Projekte, Arbeitgeber oder Personen taugt das nicht: die Liste waere
# selbst die Preisgabe. Diese Liste liegt deshalb wie die private Pool-Liste
# ausserhalb des Bestands und wird ueber dieselben Ausweichorte gefunden. Fehlt
# sie (CI, fremder Clone), wird der Check hoerbar uebersprungen.
#
# Format: ein Begriff je Zeile, Kommentare mit #. Gesucht wird woertlich und ohne
# Ruecksicht auf Gross-/Kleinschreibung. Meldungen nennen den Begriff nicht,
# nur seine Zeilennummer in der Liste — die Ausgabe landet im Evidenz-Bericht.
#
# Praefix "low:" (TODO 2542ef85): "low: <begriff>" markiert einen schwachen
# Begriff. Er bleibt auf der Liste, ein Treffer ist aber nur eine Warnung
# ("privater Begriff, schwach") — kein Verstoss, kein Exit 1, weder im Bestand
# noch beim Commit oder Push; der Evidenz-Workflow wertet ihn als LOW. Gedacht
# fuer Begriffe, die sichtbar bleiben sollen, aber keinen Release aufhalten.
# ---------------------------------------------------------------------------
HYGIENE_PRIVATE_TERMS_FILE="scripts/ci/hygiene-private-terms.txt"

# hygiene_private_terms_path <repo-root> — wie hygiene_private_list_path, fuer
# die Begriffsliste ($HYGIENE_PRIVATE_TERMS, dann die Nutzerkonfiguration).
hygiene_private_terms_path() {
  local root="$1" c
  for c in "$root/$HYGIENE_PRIVATE_TERMS_FILE" \
           "${HYGIENE_PRIVATE_TERMS:-}" \
           "${XDG_CONFIG_HOME:-${HOME:-}/.config}/mysuricates/hygiene-private-terms.txt"; do
    [ -n "$c" ] && [ -f "$c" ] && { printf '%s\n' "$c"; return 0; }
  done
  return 1
}

# hygiene_private_terms_load <liste> — der einzige Parser der Begriffsliste. Fuellt
# drei parallele Arrays:
#   HYGIENE_TERMS       Begriff (getrimmt, ohne Kommentar und ohne Praefix)
#   HYGIENE_TERM_LINES  Zeilennummer in der Liste — Meldungen nennen nur sie
#   HYGIENE_TERM_WEAK   1 = schwach (Praefix low:), 0 = normal
# public-hygiene.sh (Bestand und hinzugefuegte Zeilen) und pre-push-checks.sh
# (Commit-Nachrichten) lesen die Liste nur hierueber. Vorher hatte jede Pruefung
# ihre eigene Schleife — eine Formatregel an einer Stelle fehlte an der anderen,
# und ein "low:"-Eintrag waere dort als woertlicher Begriff stumm ins Leere gelaufen.
HYGIENE_TERMS=()
HYGIENE_TERM_LINES=()
HYGIENE_TERM_WEAK=()
hygiene_private_terms_load() {
  local list="$1" term lineno=0 weak
  HYGIENE_TERMS=(); HYGIENE_TERM_LINES=(); HYGIENE_TERM_WEAK=()
  # "|| [ -n "$term" ]" nimmt die letzte Zeile mit, wenn die Datei ohne Umbruch endet.
  while IFS= read -r term || [ -n "$term" ]; do
    lineno=$((lineno + 1))
    term="${term%%#*}"
    term="${term#"${term%%[![:space:]]*}"}"
    term="${term%"${term##*[![:space:]]}"}"
    weak=0
    case "$term" in
      low:*)
        weak=1
        term="${term#low:}"
        term="${term#"${term%%[![:space:]]*}"}"
        ;;
    esac
    [ -z "$term" ] && continue
    HYGIENE_TERMS+=("$term")
    HYGIENE_TERM_LINES+=("$lineno")
    HYGIENE_TERM_WEAK+=("$weak")
  done < "$list"
}

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
