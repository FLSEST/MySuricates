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
check "dockerignore starts with deny-all"      '[ "$(grep -v "^#" "$IGNORE" | grep -v "^$" | sed -n 1p)" = "*" ]'
check "worktrees never in the context"         '! grep -q "^!\.claude" "$IGNORE"'
check "local instance config never in image"   '! grep -q "^!ai-automation-backend/config" "$IGNORE" && ! grep -q "^!ai-automation-backend$" "$IGNORE"'
check "frontend node_modules excluded"         'grep -q "^ai-automation-frontend/node_modules$" "$IGNORE"'

echo "2) image"
check "user id at runtime, not at build"       '! grep -q "ARG HOST_UID" "$DF"'
check "backend serves the frontend"            'grep -q "AUTOMATION_SERVEFRONTEND=true" "$DF"'
check "integration key on its own volume path" 'grep -q "AUTOMATION_INTEGRATION_KEYFILE=/app/secrets/" "$DF"'
check "state paths all under /app/data"        'grep -q "AUTOMATION_DB_PATH=/app/data/" "$DF" && grep -q "AUTOMATION_BACKUP_DIR=/app/data/" "$DF"'
check "no host-built jar is copied"            '! grep -q "COPY target/" "$DF"'
check "backend stage gets the block config schema (f5153c47)" 'grep -q "^COPY ai-automation-frontend/src/data/blockConfigSchema.json /src/ai-automation-frontend/src/data/blockConfigSchema.json$" "$DF" && grep -q "ai-automation-frontend/src/data</directory>" "$R/ai-automation-backend/pom.xml" && grep -q "src/main/resources/schema</outputDirectory>" "$R/ai-automation-backend/pom.xml" && grep -qx "ai-automation-backend/src/main/resources/schema/" "$R/.gitignore"'

echo "2b) tryout stage (40c31211)"
check "compose builds the runtime stage only"  'grep -q "target: runtime" "$R/deploy/compose.yml"'
check "runtime stage comes before tryout"      '[ "$(grep -n "AS runtime$" "$DF" | cut -d: -f1)" -lt "$(grep -n "AS tryout$" "$DF" | cut -d: -f1)" ]'
check "tryout builds on runtime"               'grep -q "^FROM runtime AS tryout$" "$DF"'
check "tryout brings the AI CLIs"              'grep -q "@anthropic-ai/claude-code" "$DF" && grep -q "@openai/codex" "$DF" && grep -q "@earendil-works/pi-coding-agent" "$DF"'
PI_PIN="$(. "$R/scripts/lib/provider-catalog.generated.sh"; printf '%s' "${PROVIDER__pi__install##*@}")"
check "tryout pins pi like the installer catalog (c3a4cbf6)" 'grep -qx "ARG PI_VERSION=$PI_PIN" "$DF" && grep -qF "@earendil-works/pi-coding-agent@\${PI_VERSION}" "$DF"'
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
# The test instance (:8090) does not tag workflows via AI (5d008bba) - Cypress creates workflows there
check "docker-compose.test.yml: no AI auto-tagging" 'grep -qx "      - AUTOMATION_DISCOVERY_AUTO_TAGGING_ENABLED=false" "$R/ai-automation-backend/docker-compose.test.yml"'
check "Dockerfile.stable prepares /app/secrets"  'grep -q "/app/secrets" "$R/ai-automation-backend/docker/Dockerfile.stable"'
check "backend entrypoint hands /app/secrets to the app user" 'grep -q "chown .*/app/secrets" "$BEP" && grep -q "chmod 700 /app/secrets" "$BEP"'

echo "5) agent-devops: third-party APT repos never frozen in an old layer cache (97e1529d)"
ADF="$R/ai-automation-backend/docker/Dockerfile.agent-devops"; DC="$R/ai-automation-backend/docker-compose.yml"
line_of() { grep -n "$1" "$ADF" | sed -n 1p | cut -d: -f1; }
check "APT_REFRESH comes before the gh and the Docker repo" '[ "$(line_of "^ARG APT_REFRESH")" -lt "$(line_of "cli.github.com/packages/githubcli")" ] && [ "$(line_of "^ARG APT_REFRESH")" -lt "$(line_of "download.docker.com")" ]'
check "pinned binaries stay before it (cached)"   '[ "$(line_of "^ARG GITLEAKS_VERSION")" -lt "$(line_of "^ARG APT_REFRESH")" ] && [ "$(line_of "^ARG TRIVY_VERSION")" -lt "$(line_of "^ARG APT_REFRESH")" ]'
check "the gh layer uses APT_REFRESH itself"      'grep -q "RUN echo \"APT_REFRESH=\${APT_REFRESH" "$ADF"'
check "compose passes AGENT_APT_REFRESH"          'grep -q "APT_REFRESH: \${AGENT_APT_REFRESH:-}" "$DC"'
check "pinned checksums are 64 hex chars"         '[ "$(grep -cE "^ARG (GITLEAKS|TRIVY)_LINUX_(X64|ARM64)_SHA256=[0-9a-f]{64}$" "$ADF")" -eq 4 ]'

echo "5b) the same in every image: third-party repos and unpinned packages refreshable (8b7a39b9)"
# deploy/Dockerfile and Dockerfile.stable fetch NodeSource and Docker from foreign repos, agent-python
# unpinned pip packages - everything stalled at the first build. Every tracked Dockerfile with a
# foreign repo needs the ARG before it, a future one too.
lof() { grep -n "$2" "$1" | sed -n 1p | cut -d: -f1; }   # FILE PATTERN - number of the first line
SDF="$R/ai-automation-backend/docker/Dockerfile.stable"; PDF="$R/ai-automation-backend/docker/Dockerfile.agent-python"
DFS="$(cd "$R" && git ls-files '*Dockerfile*' 2>/dev/null)"
check "Dockerfiles found (deploy, stable, agent-devops)" 'printf "%s\n" "$DFS" | grep -x deploy/Dockerfile >/dev/null && printf "%s\n" "$DFS" | grep -x ai-automation-backend/docker/Dockerfile.stable >/dev/null && printf "%s\n" "$DFS" | grep -x ai-automation-backend/docker/Dockerfile.agent-devops >/dev/null'
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

echo "5c) instance ports only on 127.0.0.1 unless switched on purpose"
# A port mapping without an address ("9091:9091") opens Docker on all interfaces, past the
# host firewall. Every instance compose file therefore names an address with default 127.0.0.1
# or publishes nothing itself (Stable/Test: the override file of staging.sh does that).
# docker-compose.yml (agent images, legacy) is exempt.
for c in deploy/compose.yml ai-automation-backend/docker-compose.stable.yml ai-automation-backend/docker-compose.test.yml ai-automation-backend/docker-compose.dev-container.yml; do
  check "$c: no port on all addresses"   '! grep -qE "^[[:space:]]+- \"?(\\\$\{[A-Za-z_]+(:-[0-9]+)?\}|[0-9]+):[0-9]+\"?[[:space:]]*$" "$R/$c"'
  check "$c: an address, default 127.0.0.1, or no port" '! grep -qE "^[[:space:]]+ports:" "$R/$c" || grep -qE "(_BIND:-127\.0\.0\.1\}:|host_ip: \"\\\$\{[A-Z_]+_BIND:-127\.0\.0\.1\}\")" "$R/$c"'
done
check "staging.sh: port on 127.0.0.1 unless STABLE_/TEST_BIND" 'grep -q "staging_setting \"\$env\" BIND 127.0.0.1" "$R/scripts/staging.sh"'
check "staging.sh: socket off unless switched on"             'grep -q "staging_setting \"\$env\" DOCKER_SOCKET 0" "$R/scripts/staging.sh"'
check "dev starter: socket off unless switched on"            'grep -q "MYSURICATES_DEV_DOCKER_SOCKET:-0" "$R/scripts/start-mysuricates-dev.sh"'

echo "5d) agent images carry the sha256 of their Dockerfile (244e5de5)"
# The container overview calls an image outdated when its label differs from the catalog. The label must
# be the last instruction before CMD (a change above still reuses cached layers), compose must pass it
# for every agent image, and scripts/build-agent-images.sh must compute it from the Dockerfile.
BUILD="$R/scripts/build-agent-images.sh"
for svc in agent-node agent-python agent-devops agent-java agent-analysis agent-workflow; do
  f="$R/ai-automation-backend/docker/Dockerfile.$svc"
  check "$svc: label is the last instruction before CMD" \
    '[ "$(grep -vE "^[[:space:]]*(#|$)" "$f" | tail -3 | sed -n 1,2p | tr "\n" "|")" = "ARG DOCKERFILE_SHA256=|LABEL org.mysuricates.dockerfile-sha256=\$DOCKERFILE_SHA256|" ]'
  up="$(printf '%s' "${svc#agent-}" | tr '[:lower:]' '[:upper:]')"
  check "$svc: compose passes AGENT_${up}_DOCKERFILE_SHA256" 'grep -q "DOCKERFILE_SHA256: \${AGENT_${up}_DOCKERFILE_SHA256:-}" "$DC"'
done
BTMP="$(mktemp -d)"
mkdir -p "$BTMP/bin"
# Docker double: records its arguments and the identity variables compose would receive.
cat > "$BTMP/bin/docker" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" > "$BUILD_RECORD.args"
env | grep '_DOCKERFILE_SHA256=' | sort > "$BUILD_RECORD.env"
SH
chmod +x "$BTMP/bin/docker"
BUILD_RECORD="$BTMP/all" PATH="$BTMP/bin:$PATH" bash "$BUILD" > /dev/null 2>&1; RC=$?
check "build script: exit 0 with all services" '[ "$RC" -eq 0 ] && grep -qx "compose --profile build-only build agent-node agent-python agent-devops agent-java agent-analysis agent-workflow" "$BTMP/all.args"'
for svc in agent-node agent-python agent-devops agent-java agent-analysis agent-workflow; do
  up="$(printf '%s' "${svc#agent-}" | tr '[:lower:]' '[:upper:]')"
  sum="$(python3 -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$R/ai-automation-backend/docker/Dockerfile.$svc")"
  check "build script: $svc gets the sha256 of its Dockerfile" 'grep -qx "AGENT_${up}_DOCKERFILE_SHA256=$sum" "$BTMP/all.env"'
done
BUILD_RECORD="$BTMP/one" PATH="$BTMP/bin:$PATH" bash "$BUILD" agent-devops > /dev/null 2>&1
check "build script: a single service builds only that one" 'grep -qx "compose --profile build-only build agent-devops" "$BTMP/one.args"'
BUILD_RECORD="$BTMP/bad" PATH="$BTMP/bin:$PATH" bash "$BUILD" agent-unknown > /dev/null 2>&1; RC=$?
check "build script: unknown service is exit 2, docker never called" '[ "$RC" -eq 2 ] && [ ! -e "$BTMP/bad.args" ]'
rm -rf "$BTMP"

echo "6) health checks ask the own instance directly, never through a proxy (cf662c01)"
# Docker passes proxy variables into the container (~/.docker/config.json, "proxies"), and curl 8.5 sends
# localhost through them too: the check failed at the proxy, the container never became healthy, up waited
# 300 s. Every health check - HEALTHCHECK in the Dockerfile, healthcheck.test in a compose file that
# replaces it - therefore carries --noproxy '*' like scripts/lib/local-curl.sh. Searched in all tracked files.
# hc_lines - per check FILE<TAB>COMMAND: the CMD of a HEALTHCHECK instruction (shell form, continuation
# lines joined) or the test list of a compose file, as it stands
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
check "found: HEALTHCHECK and compose healthcheck"   'printf "%s\n" "$HC" | grep "^deploy/Dockerfile${T}curl " >/dev/null && printf "%s\n" "$HC" | grep "^ai-automation-backend/docker-compose.test.yml${T}\[\"CMD\", \"curl\"" >/dev/null'
NAKED_HC="$(printf '%s\n' "$HC" | grep curl | grep -vF -e "${T}curl --noproxy '*' " -e '["CMD", "curl", "--noproxy", "*", ')"
check "every health check curl carries --noproxy '*'" '[ -z "$NAKED_HC" ] || { printf "%s\n" "$NAKED_HC" | sed "s/^/         /"; false; }'
# And the way Docker runs them - shell form via sh -c, in the directory with files (an unquoted *
# would become their names), against a fake instance on the check's port, the fake proxy in every
# variable curl reads. The proxy records what arrives at it: it must get nothing.
if command -v python3 >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then
  TMP="$(mktemp -d)"; FAKE="$HERE/fake_instance.py"; PXLOG="$TMP/proxy.log"; : > "$PXLOG"
  freeport() { local p="$1"; while (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; do p=$((p + 1)); done; echo "$p"; }
  serve() {  # MODE PORT … - start fake_instance.py detached; prints the PID as soon as the port answers
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
  # proxied COMMAND - sh -c COMMAND with the fake proxy in every variable; the proxy log starts empty
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
