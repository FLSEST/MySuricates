#!/usr/bin/env bash
# Tests for scripts/public-readiness/dependency-risk-scan.sh --with-tools and
# dep_vuln_check.py (TODO 0f6d23b2) - without network: fixtures instead of npm/mvn/OSV.
# Run: bash scripts/tests/test-dependency-risk-scan.sh   (exit 0 = all checks ok)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SCAN="$REPO/scripts/public-readiness/dependency-risk-scan.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

npm_json() {  # $1 = high count
  printf '{"vulnerabilities":{%s},"metadata":{"vulnerabilities":{"info":0,"low":0,"moderate":1,"high":%s,"critical":0,"total":%s}}}\n' \
    "$([ "$1" -gt 0 ] && printf '"bad-pkg":{"severity":"high"},' )\"ok-pkg\":{\"severity\":\"moderate\"}" "$1" "$(( $1 + 1 ))"
}
npm_json 0 > "$TMP/npm-clean.json"
npm_json 1 > "$TMP/npm-high.json"

# Format of mvn dependency:list -DoutputFile (with color codes and module suffix)
printf '\nThe following files have been resolved:\n   org.example:lib-a:jar:1.0:compile\033[36m -- module lib.a\033[0;1m [auto]\033[m\n   org.example:lib-b:jar:tests:2.0:runtime\n' > "$TMP/deps.txt"

osv() {  # $1 = severity of the hits on lib-a or "none"
  if [ "$1" = none ]; then
    printf '{"batch":{"results":[{},{}]},"vulns":{}}\n'
  else
    printf '{"batch":{"results":[{"vulns":[{"id":"GHSA-test"}]},{}]},"vulns":{"GHSA-test":{"database_specific":{"severity":"%s"}}}}\n' "$1"
  fi
}
osv none > "$TMP/osv-none.json"; osv HIGH > "$TMP/osv-high.json"; osv MODERATE > "$TMP/osv-mod.json"

run() {  # $1 = npm fixture, $2 = osv fixture
  (cd "$REPO" && NPM_AUDIT_JSON_FILE="$1" MVN_DEPS_FILE="$TMP/deps.txt" OSV_FIXTURE="$2" bash "$SCAN" --with-tools 2>&1)
}

echo "1) Sauber: npm ohne high/critical, OSV ohne Treffer"
OUT=$(run "$TMP/npm-clean.json" "$TMP/osv-none.json")
check "Gate GREEN"                         'grep -q "^gate_status: GREEN" <<<"$OUT"'
check "Kategorie dependency-risk"          'grep -q "^gate_category: dependency-risk" <<<"$OUT"'
check "npm-Zaehlung da"                    'grep -q "^npm_audit_high: 0" <<<"$OUT"'
check "beide Koordinaten geprueft"         'grep -q "^osv_maven_packages_checked: 2" <<<"$OUT"'
check "kein todo_item"                     '! grep -q "^todo_item:" <<<"$OUT"'

echo "2) npm high schlaegt das Gate"
OUT=$(run "$TMP/npm-high.json" "$TMP/osv-none.json")
check "Gate YELLOW"                        'grep -q "^gate_status: YELLOW" <<<"$OUT"'
check "Grund npm-high-critical"            'grep -q "npm-high-critical" <<<"$OUT"'
check "Paketname genannt"                  'grep -q "^- bad-pkg" <<<"$OUT"'
check "todo_item da"                       'grep -q "^todo_item:" <<<"$OUT"'

echo "3) OSV-Treffer HIGH schlaegt das Gate"
OUT=$(run "$TMP/npm-clean.json" "$TMP/osv-high.json")
check "Gate YELLOW"                        'grep -q "^gate_status: YELLOW" <<<"$OUT"'
check "Grund maven-high-critical"          'grep -q "maven-high-critical" <<<"$OUT"'
check "Koordinate mit ID und Schweregrad"  'grep -q "^- org.example:lib-a:1.0: GHSA-test (HIGH)" <<<"$OUT"'

echo "4) OSV-Treffer MODERATE wird gezeigt, laesst das Gate aber gruen"
OUT=$(run "$TMP/npm-clean.json" "$TMP/osv-mod.json")
check "Gate GREEN"                         'grep -q "^gate_status: GREEN" <<<"$OUT"'
check "Treffer gezaehlt"                   'grep -q "^osv_maven_vulnerable_packages: 1" <<<"$OUT"'
check "keine high/unknown"                 'grep -q "^osv_maven_high_or_unknown_packages: 0" <<<"$OUT"'

echo "5) Ausgefallene Pruefung zaehlt als offen, nie als sauber"
OUT=$(run "$TMP/npm-clean.json" "$TMP/fehlt.json")
check "OSV unavailable gemeldet"           'grep -q "^osv_maven: unavailable" <<<"$OUT"'
check "Gate YELLOW"                        'grep -q "^gate_status: YELLOW" <<<"$OUT"'
check "Grund osv-unavailable"              'grep -q "osv-unavailable" <<<"$OUT"'
OUT=$(run "$TMP/fehlt.json" "$TMP/osv-none.json")
check "npm unavailable gemeldet"           'grep -q "^npm_audit: unavailable" <<<"$OUT"'
check "Gate YELLOW"                        'grep -q "^gate_status: YELLOW" <<<"$OUT"'

echo "6) Ohne --with-tools bleibt es beim Inventar"
OUT=$(cd "$REPO" && NPM_AUDIT_JSON_FILE="$TMP/npm-high.json" bash "$SCAN" 2>&1)
check "Gate GREEN, Kategorie Inventar"     'grep -q "^gate_category: dependency-inventory" <<<"$OUT"'
check "keine npm-Zaehlung"                 '! grep -q "^npm_audit_" <<<"$OUT"'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
