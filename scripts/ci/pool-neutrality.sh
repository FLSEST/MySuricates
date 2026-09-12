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

  m=$(grep -noE '~/[a-zA-Z]' "$f" | head -1) \
    && [ -n "$m" ] && report "$f" "PATH-HOME" "Home-relativer Pfad: ${m#*:}"

  m=$(grep -noE '(192\.168\.[0-9]|10\.[0-9]+\.[0-9]+\.[0-9]|172\.(1[6-9]|2[0-9]|3[01])\.[0-9])' "$f" | head -1) \
    && [ -n "$m" ] && report "$f" "HOST-LAN" "LAN-Adresse: ${m#*:}"

  m=$(grep -noE '[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}' "$f" | head -1) \
    && [ -n "$m" ] && report "$f" "EMAIL" "Mailadresse: ${m#*:}"

  m=$(grep -noE '[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' "$f" | head -1) \
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

if [ "$FINDINGS" -ne 0 ]; then
  echo ""
  echo "Pool-Neutralitaet: ${FINDINGS} Befund(e) in ${#FILES[@]} Definitionen."
  exit 1
fi

echo "Pool-Neutralitaet OK — ${#FILES[@]} Definitionen ohne Befund."
