#!/usr/bin/env bash
# Static checks for deploy/ (image for one instance, only Docker needed). The real build runs in
# the installer probe; this catches the regressions that would make it slow, leaky or root-owned.
# The health checks of all images also run for real, against a stand-in (python3 and curl, no Docker).
# Run: bash scripts/tests/test-deploy-files.sh   (exit 0 = all passed)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
R="$(cd "$HERE/../.." && pwd)"
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }
IGNORE="$R/.dockerignore"; DF="$R/deploy/Dockerfile"; EP="$R/deploy/entrypoint.sh"

echo "1) build context"
check "dockerignore starts with deny-all"      '[ "$(grep -v "^#" "$IGNORE" | grep -v "^$" | head -1)" = "*" ]'
check "worktrees never in the context"         '! grep -q "^!\.claude" "$IGNORE"'
check "local instance config never in image"   '! grep -q "^!ai-automation-backend/config" "$IGNORE" && ! grep -q "^!ai-automation-backend$" "$IGNORE"'
check "frontend node_modules excluded"         'grep -q "^ai-automation-frontend/node_modules$" "$IGNORE"'

echo "2) image"
check "user id at runtime, not at build"       '! grep -q "ARG HOST_UID" "$DF"'
check "backend serves the frontend"            'grep -q "AUTOMATION_SERVEFRONTEND=true" "$DF"'
check "integration key on its own volume path" 'grep -q "AUTOMATION_INTEGRATION_KEYFILE=/app/secrets/" "$DF"'
check "state paths all under /app/data"        'grep -q "AUTOMATION_DB_PATH=/app/data/" "$DF" && grep -q "AUTOMATION_BACKUP_DIR=/app/data/" "$DF"'
check "no host-built jar is copied"            '! grep -q "COPY target/" "$DF"'

echo "2b) tryout stage (40c31211)"
check "compose builds the runtime stage only"  'grep -q "target: runtime" "$R/deploy/compose.yml"'
check "runtime stage comes before tryout"      '[ "$(grep -n "AS runtime$" "$DF" | cut -d: -f1)" -lt "$(grep -n "AS tryout$" "$DF" | cut -d: -f1)" ]'
check "tryout builds on runtime"               'grep -q "^FROM runtime AS tryout$" "$DF"'
check "tryout brings the AI CLIs"              'grep -q "@anthropic-ai/claude-code" "$DF" && grep -q "@openai/codex" "$DF" && grep -q "@earendil-works/pi-coding-agent" "$DF"'
check "tryout brings pip/venv, JDK, Maven"     'grep -q "python3-venv" "$DF" && grep -q "JAVA_HOME=/opt/java/jdk" "$DF" && grep -q "/usr/local/bin/mvn" "$DF"'
check "tryout home lives on the data volume"   'grep -q "HOST_HOME=/app/data/home" "$DF" && grep -q "TRYOUT_HOME=\"/app/data/home\"" "$R/scripts/tryout.sh"'
check "tryout names itself in the UI"          'grep -q "AUTOMATION_ENVIRONMENT=Tryout" "$DF"'
check "no host mounts in the tryout"           '! grep -q "docker.sock\|/seed/\|\$HOME:" "$R/scripts/tryout.sh"'

echo "3) entrypoint"
check "entrypoint parses"                      'bash -n "$EP"'
check "drops root before starting java"        'grep -q "^exec gosu" "$EP"'
check "claude.json seeded writable"            'grep -q "/seed/.claude.json" "$EP"'
check "own provider homes for the user"        'grep -q "CLAUDE_CONFIG_DIR" "$EP" && grep -q "CODEX_HOME" "$EP" && grep -q "PI_CODING_AGENT_DIR" "$EP"'
check "host login files are never chowned"     'grep -q -- "-path /app/data/providers -prune" "$EP" && ! grep -qE "chown -R .*/app/data" "$EP"'

echo "3b) Stable/Test entrypoint (ai-automation-backend/docker/entrypoint.sh)"
BEP="$R/ai-automation-backend/docker/entrypoint.sh"
check "entrypoint parses"                      'bash -n "$BEP"'
check "own provider homes for the user"        'grep -q "CLAUDE_CONFIG_DIR" "$BEP" && grep -q "CODEX_HOME" "$BEP" && grep -q "PI_CODING_AGENT_DIR" "$BEP"'
check "drops root before starting java"        'grep -q "^exec gosu" "$BEP"'

echo "4) keys never in the container layer (3fb2242c)"
check "deploy: seal key on the secrets volume" 'grep -q "AUTOMATION_SEAL_KEY_FILE=/app/secrets/" "$DF"'
for f in docker-compose.stable.yml docker-compose.test.yml docker-compose.dev-container.yml; do
  C="$R/ai-automation-backend/$f"
  check "$f: both key files on /app/secrets" 'grep -q "AUTOMATION_INTEGRATION_KEY_FILE=/app/secrets/" "$C" && grep -q "AUTOMATION_SEAL_KEY_FILE=/app/secrets/" "$C"'
  check "$f: own secrets volume, declared"   'grep -qE "^      - [a-z-]+-secrets:/app/secrets$" "$C" && grep -qE "^  [a-z-]+-secrets:$" "$C"'
done
check "Dockerfile.stable prepares /app/secrets"  'grep -q "/app/secrets" "$R/ai-automation-backend/docker/Dockerfile.stable"'
check "backend entrypoint hands /app/secrets to the app user" 'grep -q "chown .*/app/secrets" "$BEP" && grep -q "chmod 700 /app/secrets" "$BEP"'

echo "5) agent-devops: third-party APT repos never frozen in an old layer cache (97e1529d)"
ADF="$R/ai-automation-backend/docker/Dockerfile.agent-devops"; DC="$R/ai-automation-backend/docker-compose.yml"
line_of() { grep -n "$1" "$ADF" | head -n 1 | cut -d: -f1; }
check "APT_REFRESH comes before the gh and the Docker repo" '[ "$(line_of "^ARG APT_REFRESH")" -lt "$(line_of "cli.github.com/packages/githubcli")" ] && [ "$(line_of "^ARG APT_REFRESH")" -lt "$(line_of "download.docker.com")" ]'
check "pinned binaries stay before it (cached)"   '[ "$(line_of "^ARG GITLEAKS_VERSION")" -lt "$(line_of "^ARG APT_REFRESH")" ] && [ "$(line_of "^ARG TRIVY_VERSION")" -lt "$(line_of "^ARG APT_REFRESH")" ]'
check "the gh layer uses APT_REFRESH itself"      'grep -q "RUN echo \"APT_REFRESH=\${APT_REFRESH" "$ADF"'
check "compose passes AGENT_APT_REFRESH"          'grep -q "APT_REFRESH: \${AGENT_APT_REFRESH:-}" "$DC"'
check "pinned checksums are 64 hex chars"         '[ "$(grep -cE "^ARG (GITLEAKS|TRIVY)_LINUX_(X64|ARM64)_SHA256=[0-9a-f]{64}$" "$ADF")" -eq 4 ]'

echo "5b) the same in every image: third-party repos and unpinned packages refreshable (8b7a39b9)"
# deploy/Dockerfile und Dockerfile.stable holen NodeSource und Docker aus Fremd-Repos, agent-python
# ungepinnte pip-Pakete — alles blieb beim ersten Bau stehen. Jedes getrackte Dockerfile mit einem
# Fremd-Repo braucht das ARG davor, auch ein kuenftiges.
lof() { grep -n "$2" "$1" | head -n 1 | cut -d: -f1; }   # DATEI MUSTER — Nummer der ersten Zeile
SDF="$R/ai-automation-backend/docker/Dockerfile.stable"; PDF="$R/ai-automation-backend/docker/Dockerfile.agent-python"
DFS="$(cd "$R" && git ls-files '*Dockerfile*' 2>/dev/null)"
check "Dockerfiles found (deploy, stable, agent-devops)" 'printf "%s\n" "$DFS" | grep -qx deploy/Dockerfile && printf "%s\n" "$DFS" | grep -qx ai-automation-backend/docker/Dockerfile.stable && printf "%s\n" "$DFS" | grep -qx ai-automation-backend/docker/Dockerfile.agent-devops'
for f in $DFS; do
  grep -q "sources.list.d/" "$R/$f" || continue
  check "$f: every third-party repo behind ARG APT_REFRESH" '[ -n "$(lof "$R/$f" "^ARG APT_REFRESH=")" ] && [ "$(lof "$R/$f" "^ARG APT_REFRESH=")" -lt "$(lof "$R/$f" "sources.list.d/")" ] && grep -q "RUN echo \"APT_REFRESH=\${APT_REFRESH" "$R/$f"'
done
for f in "$DF" "$SDF"; do
  check "${f#"$R"/}: base packages stay before it (cached)" '[ "$(lof "$f" "curl git gosu")" -lt "$(lof "$f" "^ARG APT_REFRESH=")" ]'
done
check "deploy/compose.yml passes MYSURICATES_APT_REFRESH" 'grep -q "APT_REFRESH: \${MYSURICATES_APT_REFRESH:-}" "$R/deploy/compose.yml"'
for c in docker-compose.stable.yml docker-compose.test.yml docker-compose.dev-container.yml; do
  check "$c passes MYSURICATES_APT_REFRESH" 'grep -q "APT_REFRESH: \${MYSURICATES_APT_REFRESH:-}" "$R/ai-automation-backend/$c"'
done
check "agent-python: pip packages behind PIP_REFRESH" '[ -n "$(lof "$PDF" "^ARG PIP_REFRESH=")" ] && [ "$(lof "$PDF" "^ARG PIP_REFRESH=")" -lt "$(lof "$PDF" "pip install")" ] && grep -q "RUN echo \"PIP_REFRESH=\${PIP_REFRESH" "$PDF"'
check "compose: the same AGENT_APT_REFRESH for agent-python" 'grep -q "PIP_REFRESH: \${AGENT_APT_REFRESH:-}" "$DC"'

echo "6) health checks ask the own instance directly, never through a proxy (cf662c01)"
# Docker reicht Proxy-Variablen in den Container (~/.docker/config.json, „proxies“), und curl 8.5 schickt
# auch localhost ueber sie: die Pruefung scheiterte am Proxy, der Container wurde nie gesund, up wartete
# 300 s. Jede Health-Pruefung — HEALTHCHECK im Dockerfile, healthcheck.test in einer Compose-Datei, die
# ihn ersetzt — traegt deshalb --noproxy '*' wie scripts/lib/local-curl.sh. Gesucht in allen getrackten Dateien.
# hc_lines — je Pruefung DATEI<TAB>BEFEHL: die CMD einer HEALTHCHECK-Anweisung (Shell-Form, Fortsetzungs-
# zeilen verbunden) oder die test-Liste einer Compose-Datei, wie sie dasteht
hc_lines() {
  local files f
  files="$(git -C "$R" ls-files)"
  for f in $(printf '%s\n' "$files" | grep -E '(^|/)Dockerfile[^/]*$'); do
    awk -v f="$f" '/^HEALTHCHECK[[:space:]]/ { on = 1; s = "" }
      on { l = $0; more = sub(/\\[[:space:]]*$/, "", l); s = s " " l
           if (!more) { sub(/^.*[[:space:]]CMD[[:space:]]+/, "", s); print f "\t" s; on = 0 } }' "$R/$f"
  done
  for f in $(printf '%s\n' "$files" | grep -E '(^|/)(docker-)?compose[^/]*\.ya?ml$'); do
    awk -v f="$f" '/^[[:space:]]+test:[[:space:]]*\[/ { sub(/^[[:space:]]*test:[[:space:]]*/, ""); print f "\t" $0 }' "$R/$f"
  done
}
HC="$(hc_lines)"; T="$(printf '\t')"
check "found: HEALTHCHECK and compose healthcheck"   'printf "%s\n" "$HC" | grep -q "^deploy/Dockerfile${T}curl " && printf "%s\n" "$HC" | grep -q "^ai-automation-backend/docker-compose.yml${T}\[\"CMD\", \"curl\""'
NAKED_HC="$(printf '%s\n' "$HC" | grep curl | grep -vF -e "${T}curl --noproxy '*' " -e '["CMD", "curl", "--noproxy", "*", ')"
check "every health check curl carries --noproxy '*'" '[ -z "$NAKED_HC" ] || { printf "%s\n" "$NAKED_HC" | sed "s/^/         /"; false; }'
# Und so, wie Docker sie ausfuehrt — Shell-Form ueber sh -c, im Verzeichnis mit Dateien (ein ungequotetes *
# wuerde zu ihren Namen), gegen eine Fake-Instanz auf dem Port der Pruefung, den Fake-Proxy in jeder
# Variablen, die curl liest. Der Proxy schreibt auf, was bei ihm ankommt: er darf nichts bekommen.
if command -v python3 >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then
  TMP="$(mktemp -d)"; FAKE="$HERE/fake_instance.py"; PXLOG="$TMP/proxy.log"; : > "$PXLOG"
  freeport() { local p="$1"; while (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; do p=$((p + 1)); done; echo "$p"; }
  serve() {  # MODUS PORT … — fake_instance.py losgeloest starten; gibt die PID aus, sobald der Port antwortet
    local pid
    pid="$( (python3 "$FAKE" "$@" > /dev/null 2>&1 & echo $!) )"
    for _ in $(seq 1 100); do (exec 3<>"/dev/tcp/127.0.0.1/$2") 2>/dev/null && break; sleep 0.05; done
    echo "$pid"
  }
  APORT="$(freeport 19580)"; API_PID="$(serve api "$APORT" key 0)"
  PXPORT="$(freeport $((APORT + 1)))"; PX_PID="$(serve proxy "$PXPORT" "$PXLOG")"
  trap 'kill "$API_PID" "$PX_PID" 2>/dev/null; rm -rf "$TMP"' EXIT
  mkdir -p "$TMP/app"; touch "$TMP/app/app.jar" "$TMP/app/entrypoint.sh"
  PX="http://127.0.0.1:$PXPORT"
  # proxied BEFEHL — sh -c BEFEHL mit dem Fake-Proxy in jeder Variablen; der Proxy-Log beginnt leer
  proxied() { : > "$PXLOG"; ( cd "$TMP/app" && env -u no_proxy -u NO_PROXY http_proxy="$PX" https_proxy="$PX" all_proxy="$PX" HTTP_PROXY="$PX" HTTPS_PROXY="$PX" ALL_PROXY="$PX" sh -c "$1" ) >/dev/null 2>&1; }
  untouched() { [ ! -s "$PXLOG" ]; }
  proxied "curl -fsS http://localhost:$APORT/api/v1/system/health"
  check "control: a plain curl goes through the fake proxy" '! untouched'
  while IFS="$T" read -r f cmd; do
    case "$cmd" in
      \[*) cmd="$(printf '%s' "$cmd" | python3 -c 'import json, shlex, sys; a = json.load(sys.stdin); print(shlex.join(a[1:]) if a[0] == "CMD" else a[1])')" ;;
    esac
    cmd="$(printf '%s' "$cmd" | sed "s#localhost:[0-9][0-9]*#localhost:$APORT#g")"
    check "$f: healthy although a proxy is set" 'proxied "$cmd" && untouched'
  done <<< "$HC"
else
  echo "  skip (no python3 or curl)"
fi

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
