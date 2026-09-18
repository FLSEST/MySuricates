#!/usr/bin/env bash
#
# Pool-Neutralität: prüft die getrackten Definitionen unter workflow-pool/ auf
# Dinge, die nicht in ein öffentliches Repository gehören.
#
# Der Pool ist keine Ablage, sondern eine Funktionsfläche: AUTOMATION_POOL_DIR
# zeigt per Default darauf, und über GET /pool plus POST /pool/{id}/install
# übernimmt jeder Leser einen Eintrag in seine Instanz. Was hier steht, wird also
# mitgeliefert und ausgeführt.
#
# Dieses Skript ist die einzige Quelle des Musterkatalogs. Das Built-in-Template
# "pool-neutrality-audit" ruft es auf, statt die Muster ein zweites Mal in einem
# JSON-Feld zu führen, wo sie weder testbar noch aus der CI aufrufbar wären.
#
# Usage:  scripts/ci/pool-neutrality.sh [--all] [verzeichnis]
#           ohne --all: nur getrackte Definitionen (das ist die Release-Frage)
#           mit  --all: jede .json im Verzeichnis, auch ignorierte — so prueft das
#                       Built-in-Template den Pool einer laufenden Instanz
# Exit:   0 = sauber, 1 = Befunde
#
set -uo pipefail

# Regelbasis wie in public-hygiene.sh aus dem Skriptpfad, nicht aus git: das Skript
# wird auch mit fremdem Arbeitsverzeichnis aufgerufen (Built-in-Template).
POOL_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
POOL_RULES_FILE="$POOL_SCRIPT_DIR/../lib/hygiene-rules.sh"
if [ ! -f "$POOL_RULES_FILE" ]; then
  echo "Pool-Neutralitaet: Regeldatei fehlt: $POOL_RULES_FILE" >&2
  exit 2
fi
# shellcheck source=../lib/hygiene-rules.sh
. "$POOL_RULES_FILE"

SCAN_ALL=0
if [ "${1:-}" = "--all" ]; then
  SCAN_ALL=1
  shift
fi
DIR="${1:-workflow-pool}"
FINDINGS=0

report() {
  printf '%s\t%s\t%s\n' "$1" "$2" "$3"
  FINDINGS=$((FINDINGS + 1))
}

# ---------------------------------------------------------------------------
# Musterkatalog
#
# Zwei der ursprünglichen Muster waren unbrauchbar und sind korrigiert:
#
#   PATH-WIN  war  [A-Z]:\\[a-zA-Z]  und traf in JSON jedes deutsche
#             "METRIKEN:\n" im Prompt — 9 von 18 Dateien, alle falsch positiv.
#             Jetzt wird ein echter Laufwerksbuchstabe am Wortanfang verlangt.
#
#   BINARY    traf "#!/usr/bin/env python3", den portabel korrekten Shebang.
#             /usr/bin/env ist jetzt ausgenommen; gemeint sind fest verdrahtete
#             Werkzeugpfade wie /usr/bin/dot.
# ---------------------------------------------------------------------------
scan_file() {
  local f="$1" m

  m=$(grep -noE '(/home/[a-zA-Z_-]+/|/Users/[a-zA-Z_-]+/|/opt/[a-zA-Z]|/var/(log|lib|opt)/)' "$f" | head -1) \
    && [ -n "$m" ] && report "$f" "PATH-ABS" "absoluter Benutzer-/Systempfad: ${m#*:}"

  m=$(grep -noE '(^|[^A-Za-z0-9])[A-Z]:\\\\[A-Za-z]' "$f" | head -1) \
    && [ -n "$m" ] && report "$f" "PATH-WIN" "Windows-Pfad: ${m#*:}"

  # Punkt mit drin: ~/.mysuricates ist genauso ein Home-Pfad wie ~/projekt.
  m=$(grep -noE '~/[A-Za-z.]' "$f" | head -1) \
    && [ -n "$m" ] && report "$f" "PATH-HOME" "Home-relativer Pfad: ${m#*:}"

  m=$(grep -noE '(192\.168\.[0-9]|10\.[0-9]+\.[0-9]+\.[0-9]|172\.(1[6-9]|2[0-9]|3[01])\.[0-9])' "$f" | head -1) \
    && [ -n "$m" ] && report "$f" "HOST-LAN" "LAN-Adresse: ${m#*:}"

  m=$(grep -noE '[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}' "$f" | head -1) \
    && [ -n "$m" ] && report "$f" "EMAIL" "Mailadresse: ${m#*:}"

  # Beide Schreibweisen, wie der Sanitizer im Backend (PoolExportSanitizer).
  m=$(grep -noE '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}' "$f" | head -1) \
    && [ -n "$m" ] && report "$f" "UUID" "Instanz-UUID: ${m#*:}"

  # Fest verdrahtete Werkzeugpfade — /usr/bin/env ist der portable Shebang und zählt nicht.
  m=$(grep -noE '(/usr/local/bin/[a-zA-Z]+|/usr/bin/[a-zA-Z]+)' "$f" \
        | grep -v '/usr/bin/env' | head -1) \
    && [ -n "$m" ] && report "$f" "BINARY" "fester Werkzeugpfad: ${m#*:}"
}

# ---------------------------------------------------------------------------
# Personenbezogene Feldnamen im Default-Kontext
#
# Der Schlüssel ist das Signal, nicht der Wert: ein defaultContext mit "wohnort"
# oder "kinder_alter" ist auch dann ein Befund, wenn gerade ein Platzhalter
# darin steht — beim nächsten Export steht wieder ein echter Wert drin.
# ---------------------------------------------------------------------------
scan_context_keys() {
  python3 - "$@" <<'PY'
import json, re, sys

# Token, nicht Teilstrings: "age" darf nicht in "message" oder "package" treffen.
# Schluessel werden an _ - . und an camelCase-Grenzen zerlegt, dann Token verglichen.
PERSONAL = {
    # Personen und Haushalt
    # 'age'/'ages' bewusst NICHT einzeln: min_age_days ist ein Aufbewahrungs-
    # Parameter, kein Personendatum. child_ages greift ueber 'child'.
    'adult', 'adults', 'child', 'children', 'kid', 'kids',
    'spouse', 'partner', 'family', 'birthday', 'birthdate', 'dob',
    # Kontaktdaten
    'address', 'street', 'strasse', 'plz', 'postcode', 'zip', 'city', 'wohnort',
    'phone', 'telefon', 'handy', 'mobile', 'email', 'mail',
    # Finanzen und Amtliches
    'iban', 'konto', 'account', 'salary', 'gehalt', 'steuer', 'tax',
    'insurance', 'versicherung', 'passport', 'ausweis',
    # Bewerbung und Profil
    'cv', 'resume', 'lebenslauf', 'bewerbung', 'applicant', 'employer', 'arbeitgeber',
}
# Mehrwort-Schluessel, die erst in der Kombination personenbezogen sind.
PERSONAL_PAIRS = {('user', 'profile'), ('home', 'address'), ('child', 'ages')}

def tokens(key):
    parts = re.split(r'[_\-.\s]+', re.sub(r'(?<=[a-z0-9])(?=[A-Z])', '_', key))
    return [p.lower() for p in parts if p]

for path in sys.argv[1:]:
    try:
        with open(path, encoding='utf-8') as fh:
            data = json.load(fh)
    except Exception:
        continue
    ctx = data.get('defaultContext') or {}
    if not isinstance(ctx, dict):
        continue
    for key in ctx:
        tk = tokens(key)
        hit = sorted(set(tk) & PERSONAL)
        if not hit:
            for a, b in PERSONAL_PAIRS:
                if a in tk and b in tk:
                    hit = [f"{a}+{b}"]
                    break
        if hit:
            print(f"{path}\tCONTEXT-PII\tpersonenbezogener Schluessel im defaultContext: "
                  f"{key} (Token: {', '.join(hit)})")
PY
}

# ---------------------------------------------------------------------------
# Eigene REST-API, Zugangsdaten, direkter Datenbankzugriff (TODO 1ccdc6d5)
#
# Die Hausregel steht in CLAUDE.md: Workflow-Bloecke rufen weder die eigene
# REST-API noch die Datenbank auf. In einer mitveroeffentlichten Definition ist
# ein Prompt, der den API-Key aus app_settings holen laesst, zusaetzlich eine
# Anleitung zum Auslesen von Zugangsdaten.
#
# Die Muster sind absichtlich zweistufig: "/api/v1/" allein ist kein Befund
# (mocking-gap-audit fuehrt es als Suchmuster in einer Liste), "automation.db"
# allein auch nicht (mysuricates-performance-audit sieht nur die Dateigroesse an).
# Gemeldet wird erst die Kombination mit einem Aufruf bzw. einem Lesezugriff.
# ---------------------------------------------------------------------------
scan_api_and_credentials() {
  POOL_CREDENTIAL_MARKERS="$(printf '%s\n' "${HYGIENE_POOL_CREDENTIAL_MARKERS[@]}")" \
  POOL_API_PREFIX="$HYGIENE_POOL_API_PREFIX" \
  POOL_API_CALLERS="$(printf '%s\n' "${HYGIENE_POOL_API_CALLERS[@]}")" \
  POOL_API_ALLOWED="$(printf '%s\n' "${HYGIENE_POOL_API_ALLOWED[@]}")" \
  POOL_DB_FILES="$(printf '%s\n' "${HYGIENE_POOL_DB_FILES[@]}")" \
  POOL_DB_ACCESS="$(printf '%s\n' "${HYGIENE_POOL_DB_ACCESS[@]}")" \
  python3 - "$@" <<'PY'
import os, re, sys

def env_list(name):
    return [v for v in os.environ.get(name, '').splitlines() if v]

CREDENTIALS = env_list('POOL_CREDENTIAL_MARKERS')
API_PREFIX = os.environ.get('POOL_API_PREFIX', '/api/v1/')
API_CALLERS = env_list('POOL_API_CALLERS')
API_ALLOWED = env_list('POOL_API_ALLOWED')
DB_FILES = env_list('POOL_DB_FILES')
DB_ACCESS = env_list('POOL_DB_ACCESS')

# Wie weit um eine Fundstelle herum nach dem zweiten Kennzeichen gesucht wird.
# 200 Zeichen sind gut eine Befehlszeile — weit genug fuer "curl ... $URL",
# eng genug, dass zwei unabhaengige Stellen nicht zusammengezogen werden.
WINDOW = 200

VERB_CALL = re.compile(r'\b(GET|POST|PUT|PATCH|DELETE)\s+' + re.escape(API_PREFIX))
# Konkreter Endpunkt: nach dem Praefix muss ein Pfadsegment folgen. Das nackte
# Praefix ist ein Suchmuster, kein Aufruf — mocking-gap-audit fuehrt '/api/v1/'
# in einer Liste von Netzwerk-Kennzeichen, direkt neben 'fetch(' und 'axios.'.
PATH_AT = re.compile(re.escape(API_PREFIX) + r'[A-Za-z0-9_][A-Za-z0-9/_.{}<>-]*')


def windows(text, needle):
    start = 0
    while True:
        i = text.find(needle, start)
        if i < 0:
            return
        yield i, text[max(0, i - WINDOW):i + WINDOW]
        start = i + 1


for path in sys.argv[1:]:
    try:
        with open(path, encoding='utf-8') as fh:
            raw = fh.read()
    except OSError:
        continue

    for marker in CREDENTIALS:
        if marker in raw:
            print(f"{path}\tCREDENTIAL\tliest oder setzt Zugangsdaten: '{marker}' "
                  f"— eine mitveroeffentlichte Definition darf das nicht erklaeren")
            break

    for i, window in windows(raw, API_PREFIX):
        endpoint = PATH_AT.match(raw, i)
        if endpoint is None:
            continue
        hit = endpoint.group(0)
        if any(hit.startswith(allowed) for allowed in API_ALLOWED):
            continue
        caller = next((c for c in API_CALLERS if c in window), None)
        if caller is None and VERB_CALL.search(window):
            caller = 'HTTP-Verb'
        if caller is None:
            continue
        print(f"{path}\tSELF-API\truft die eigene REST-API auf: '{hit}' (via {caller}) "
              f"— Plattformdaten kommen als Kontextvariable, "
              f"TODOs ueber die Bloecke todo-create/todo-update/todo-close")
        break

    # sqlite3 ist fuer sich schon der Zugriff, egal welche Datei gemeint ist.
    if 'sqlite3' in raw:
        print(f"{path}\tDB-DIRECT\truft sqlite3 auf — das umgeht Hibernate und die "
              f"einzige Connection (pool-size=1)")
    else:
        for db in DB_FILES:
            access = next((a for _, w in windows(raw, db)
                           for a in DB_ACCESS if a in w), None)
            if access:
                print(f"{path}\tDB-DIRECT\tliest die Datenbank direkt "
                      f"('{db}' zusammen mit '{access.strip()}') — erlaubt ist nur, "
                      f"die Dateigroesse anzusehen (stat, du)")
                break
PY
}

# ---------------------------------------------------------------------------
# Kuratierungsregeln (docs/workflow-pool-kuratierung.md, Abschnitt 3)
#
# Die Muster oben fragen "steht hier etwas Privates drin?". Hier geht es um die
# zweite Frage: "ist das ein brauchbarer Eintrag?" — deklarierter scope, keine
# Instanz-Bindung, Beschreibung und Tags vorhanden, Blocktypen aus der Registry,
# und bei scope: portable keine Abhängigkeit auf dieses Repository.
#
# Die Listen kommen aus scripts/lib/hygiene-rules.sh und werden als Umgebungs-
# variablen durchgereicht, damit argv den Dateien gehört.
# ---------------------------------------------------------------------------
scan_definition_rules() {
  POOL_SCOPES="$(printf '%s\n' "${HYGIENE_POOL_SCOPES[@]}")" \
  POOL_BLOCK_TYPES="$(printf '%s\n' "${HYGIENE_POOL_BLOCK_TYPES[@]}")" \
  POOL_REPO_MARKERS="$(printf '%s\n' "${HYGIENE_POOL_REPO_MARKERS[@]}")" \
  POOL_INSTANCE_KEYS="$(printf '%s\n' "${HYGIENE_POOL_INSTANCE_KEYS[@]}")" \
  python3 - "$@" <<'PY'
import json, os, sys

def env_list(name):
    return [v for v in os.environ.get(name, '').splitlines() if v]

SCOPES = env_list('POOL_SCOPES')
BLOCK_TYPES = set(env_list('POOL_BLOCK_TYPES'))
REPO_MARKERS = env_list('POOL_REPO_MARKERS')
def normalize_key(key):
    """Wie PoolExportSanitizer.normalize: klein, ohne '_' und '-'.

    Der Export vergleicht normalisiert und faengt damit auch Schreibweisen, die in der
    generierten Liste nicht wortwoertlich stehen (project-id). Das Gate muss dieselbe
    Semantik haben, sonst beanstandet es weniger, als der Export entfernt (TODO 96640e6f).
    """
    return key.lower().replace('_', '').replace('-', '')

INSTANCE_KEYS = {normalize_key(k) for k in env_list('POOL_INSTANCE_KEYS')}

findings = []

def report(path, tag, desc):
    findings.append(f"{path}\t{tag}\t{desc}")

def localized_ok(value):
    """Ein {de, en}-Feld mit mindestens einem nicht-leeren Text."""
    return isinstance(value, dict) and any(
        isinstance(v, str) and v.strip() for v in value.values())

def instance_bound(value):
    """Ein Wert, der eine laufende Instanz bezeichnet — Platzhalter zaehlen nicht."""
    if value is None:
        return False
    if not isinstance(value, str):
        return True
    return bool(value.strip()) and '{{' not in value

def walk_keys(node, path, out):
    if isinstance(node, dict):
        for key, value in node.items():
            here = f"{path}.{key}" if path else key
            if normalize_key(key) in INSTANCE_KEYS and instance_bound(value):
                out.append((here, key))
            walk_keys(value, here, out)
    elif isinstance(node, list):
        for i, item in enumerate(node):
            walk_keys(item, f"{path}[{i}]", out)

# Config-Schluessel, unter denen wieder Bloecke stehen. Es gibt genau zwei, beide vom
# switch-Block: cases[].blocks und default (SwitchBlockExecutor.java:37-38,59,67, gespiegelt
# in ExecutionMode.java:68-76). Die anderen Wrapper verschachteln keine Bloecke, sondern
# fuehren Strings aus: conditional then_command/else_command, foreach/while/retry command.
#
# 'cases' selbst steht bewusst NICHT hier: seine Elemente sind Fall-Objekte ({when, blocks}),
# keine Bloecke. Die Rekursion erreicht ihr 'blocks' im naechsten Schritt ohnehin — und ein
# Fall-Objekt mit einem eigenen 'type'-Feld wuerde sonst faelschlich als Block gezaehlt.
BLOCK_CONTAINER_KEYS = {'blocks', 'default'}

def collect_types(node, out, is_block=False):
    """Blocktypen einsammeln.

    is_block gilt fuer Werte, die unter einem Blockschluessel stehen — dort ist jedes
    Objekt ein Block, auch ohne 'config' (das Feld ist optional). Anderswo im Baum zaehlt
    ein Objekt nur mit 'type' UND 'config' als Block: sonst wuerde ein Konfigurationswert
    wie {"type": "pdf"} als Blocktyp gezaehlt.
    """
    if isinstance(node, dict):
        if isinstance(node.get('type'), str) and (is_block or 'config' in node):
            out.append(node['type'])
        for key, value in node.items():
            collect_types(value, out, key in BLOCK_CONTAINER_KEYS)
    elif isinstance(node, list):
        for item in node:
            collect_types(item, out, is_block)

for path in sys.argv[1:]:
    try:
        with open(path, encoding='utf-8') as fh:
            raw = fh.read()
        data = json.loads(raw)
    except Exception as exc:                                  # noqa: BLE001
        report(path, "PARSE", f"JSON nicht lesbar: {exc}")
        continue
    if not isinstance(data, dict):
        report(path, "PARSE", "Definition ist kein JSON-Objekt")
        continue

    scope = data.get('scope')
    if scope is None:
        report(path, "SCOPE", "Pflichtfeld 'scope' fehlt (erlaubt: " + ", ".join(SCOPES) + ")")
    elif scope not in SCOPES:
        report(path, "SCOPE", f"ungueltiger scope: {scope!r} (erlaubt: " + ", ".join(SCOPES) + ")")

    if not localized_ok(data.get('name')):
        report(path, "NAME", "'name' fehlt oder ist kein {de, en}-Objekt mit Text "
                             "— der Eintrag laesst sich nicht laden")
    if not localized_ok(data.get('description')):
        report(path, "DESCRIPTION", "'description' fehlt oder ist kein {de, en}-Objekt mit Text")

    tags = data.get('tags')
    if not isinstance(tags, list) or not [t for t in tags if isinstance(t, str) and t.strip()]:
        report(path, "TAGS", "'tags' fehlt oder ist leer — ohne Tags ist der Eintrag nicht auffindbar")

    bound = []
    walk_keys(data, "", bound)
    for where, key in bound:
        tag = "PROJECT-ID" if normalize_key(key) == "projectid" else "INSTANCE-REF"
        report(path, tag, f"Instanz-Bindung unter '{where}' — der Export schreibt sie nicht mehr mit")

    types = []
    collect_types(data.get('blocks') or [], types, is_block=True)
    for unknown in sorted({t for t in types if t not in BLOCK_TYPES}):
        report(path, "BLOCK-TYPE", f"Blocktyp '{unknown}' ist in der Registry nicht vorhanden")

    if scope == "portable":
        for marker in REPO_MARKERS:
            if marker in raw:
                report(path, "REPO-DEP",
                       f"scope: portable, nutzt aber '{marker}' aus diesem Repository "
                       f"— entweder parametrisieren oder scope: repo")
                break

for line in findings:
    print(line)
PY
}

# ---------------------------------------------------------------------------
# Lauf
# ---------------------------------------------------------------------------
if [ "$SCAN_ALL" -eq 1 ]; then
  mapfile -t FILES < <(find "$DIR" -maxdepth 1 -name '*.json' | sort)
else
  mapfile -t FILES < <(git ls-files "$DIR" 2>/dev/null | grep '\.json$' || true)
fi

if [ ${#FILES[@]} -eq 0 ]; then
  echo "Keine getrackten Pool-Definitionen unter $DIR/ — nichts zu pruefen."
  exit 0
fi

for f in "${FILES[@]}"; do
  scan_file "$f"
done

CTX=$(scan_context_keys "${FILES[@]}")
if [ -n "$CTX" ]; then
  echo "$CTX"
  FINDINGS=$((FINDINGS + $(printf '%s\n' "$CTX" | grep -c .)))
fi

RULES=$(scan_definition_rules "${FILES[@]}")
if [ -n "$RULES" ]; then
  echo "$RULES"
  FINDINGS=$((FINDINGS + $(printf '%s\n' "$RULES" | grep -c .)))
fi

API=$(scan_api_and_credentials "${FILES[@]}")
if [ -n "$API" ]; then
  echo "$API"
  FINDINGS=$((FINDINGS + $(printf '%s\n' "$API" | grep -c .)))
fi

if [ "$FINDINGS" -ne 0 ]; then
  echo ""
  echo "Pool-Neutralitaet: ${FINDINGS} Befund(e) in ${#FILES[@]} Definitionen."
  exit 1
fi

echo "Pool-Neutralitaet OK — ${#FILES[@]} Definitionen ohne Befund."
