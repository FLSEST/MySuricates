#!/usr/bin/env bash
# Tests for scripts/tryout.sh — without Docker (stub logs the calls), in a throwaway git repo.
# Run: bash scripts/tests/test-tryout.sh   (exit 0 = all passed)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; R="$(cd "$HERE/../.." && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }

# Throwaway repo: scripts/tryout.sh + a Dockerfile; v0.1.0 without the tryout stage, v0.2.0 and v0.10.0 with it
REPO="$TMP/repo"; mkdir -p "$REPO/scripts/lib" "$REPO/deploy"
cp "$R/scripts/tryout.sh" "$REPO/scripts/"
cp "$R/scripts/lib/provider-login.sh" "$R/scripts/lib/data-home.sh" "$REPO/scripts/lib/"   # login commands shared with instance.sh/staging.sh
git -C "$REPO" init -q && git -C "$REPO" config user.email t@t && git -C "$REPO" config user.name t
printf 'FROM x AS runtime\n' > "$REPO/deploy/Dockerfile"
git -C "$REPO" add -A && git -C "$REPO" commit -qm one && git -C "$REPO" tag v0.1.0
printf 'FROM x AS runtime\nFROM runtime AS tryout\n' > "$REPO/deploy/Dockerfile"
git -C "$REPO" commit -qam two && git -C "$REPO" tag v0.10.0
git -C "$REPO" tag v0.2.0 HEAD~0
echo dirty >> "$REPO/deploy/Dockerfile"   # uncommitted: must never reach the build
SCRIPT="$REPO/scripts/tryout.sh"

mkdir -p "$TMP/bin"
cat > "$TMP/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$DOCKER_LOG"
case "$1 $2" in
  "build "*) cat > "$DOCKER_CTX"; exit 0 ;;
  "container inspect")
    [ -n "${EXISTS:-}" ] || [ -f "$DOCKER_RAN" ] || exit 1
    case "$*" in *Running*) echo true ;; *Health.Status*) echo healthy ;; *Config.Image*) echo mysuricates:tryout ;; esac; exit 0 ;;
  "image inspect") [ -z "${NO_IMAGE:-}" ] || exit 1; case "$*" in *image.version*) echo 0.10.0 ;; esac ;;
  "run "*) [ -z "${RUN_FAILS:-}" ] || exit 125; touch "$DOCKER_RAN"; exit 0 ;;   # from now on the container exists
  "port "*) echo "127.0.0.1:${PORT_OUT:-9095}" ;;
  "exec "*) echo KEY123 ;;
  "save "*) echo imagebytes ;;
esac
exit 0
STUB
chmod +x "$TMP/bin/docker"
export DOCKER_LOG="$TMP/docker.log" DOCKER_CTX="$TMP/ctx.tar" DOCKER_RAN="$TMP/ran" TRYOUT_HEALTH_TIMEOUT=4 PATH="$TMP/bin:$PATH"

echo "1) usage"
check "bash -n"                                'bash -n "$R/scripts/tryout.sh"'
check "--help lists the commands"              'bash "$SCRIPT" --help | grep "build|start|login|key|shell|status|logs|stop|remove|export" >/dev/null'
check "unknown command exits 2"                'bash "$SCRIPT" frobnicate >/dev/null 2>&1; [ $? -eq 2 ]'
check "port must be numeric"                   'bash "$SCRIPT" start --port abc >/dev/null 2>&1; [ $? -eq 2 ]'

echo "2) build: newest release tag, from git archive"
: > "$DOCKER_LOG"; OUT="$(bash "$SCRIPT" build 2>&1)"
check "newest by version, not by name (v0.10.0 > v0.2.0)" 'grep -q -- "-t mysuricates:0.10.0-tryout -t mysuricates:tryout" "$DOCKER_LOG"'
check "builds the tryout target from stdin"    'grep -q -- "build -f deploy/Dockerfile --target tryout" "$DOCKER_LOG" && grep -q -- " -$" "$DOCKER_LOG"'
check "APT_REFRESH passed, empty by default (8b7a39b9)" 'grep -q -- "--build-arg APT_REFRESH= -t" "$DOCKER_LOG"'
: > "$DOCKER_LOG"; MYSURICATES_APT_REFRESH=2026-10 bash "$SCRIPT" build >/dev/null 2>&1
check "... a new value from the environment"   'grep -q -- "--build-arg APT_REFRESH=2026-10 -t" "$DOCKER_LOG"'
check "version + revision labels"              'grep -q "image.version=0.10.0" "$DOCKER_LOG" && grep -q "image.revision=$(git -C "$REPO" rev-parse v0.10.0)" "$DOCKER_LOG"'
check "context is the tag, not the worktree"   'tar -xOf "$DOCKER_CTX" deploy/Dockerfile | grep -q tryout && ! tar -xOf "$DOCKER_CTX" deploy/Dockerfile | grep -q dirty'
OUT="$(bash "$SCRIPT" build --ref v0.1.0 2>&1)"; RC=$?
check "old tag without tryout stage refused"   '[ "$RC" = 2 ] && printf "%s" "$OUT" | grep -q "Tryout-Stufe"'
OUT="$(bash "$SCRIPT" build --ref nope 2>&1)"; RC=$?
check "unknown ref refused"                    '[ "$RC" = 2 ]'

echo "3) start: nothing from the host is mounted"
: > "$DOCKER_LOG"; OUT="$(bash "$SCRIPT" start --port 9123 2>&1)"
RUN="$(grep "^run " "$DOCKER_LOG")"
check "binds to localhost only"                'printf "%s" "$RUN" | grep -q -- "-p 127.0.0.1:9123:9090"'
check "only the two tryout volumes"            '[ "$(printf "%s" "$RUN" | grep -o -- "-v [^ ]*" | tr "\n" " ")" = "-v mysuricates-tryout-data:/app/data -v mysuricates-tryout-secrets:/app/secrets " ]'
check "no docker socket, no host paths"        '! printf "%s" "$RUN" | grep -q "docker.sock\|/home/\|/seed"'
check "CORS matches the chosen port"           'printf "%s" "$RUN" | grep -q "AUTOMATION_CORS_ORIGINS=http://localhost:9123"'
check "login command for the status card"      'printf "%s" "$RUN" | grep -q -- "-e MYSURICATES_LOGIN_COMMAND=scripts/tryout.sh login \[claude|codex|pi\]"'
rm -f "$DOCKER_RAN"; OUT="$(NO_IMAGE=1 bash "$SCRIPT" start 2>&1)"; RC=$?
check "missing image: clear hint"              '[ "$RC" = 2 ] && printf "%s" "$OUT" | grep -q "build"'
: > "$DOCKER_LOG"; OUT="$(EXISTS=1 PORT_OUT=9123 bash "$SCRIPT" start 2>&1)"
check "existing container is reused"           '! grep -q "^run " "$DOCKER_LOG"'
check "prints address and key"                 'printf "%s" "$OUT" | grep -q "http://localhost:9123" && printf "%s" "$OUT" | grep -q KEY123'

rm -f "$DOCKER_RAN"; python3 -c 'import socket,time;s=socket.socket();s.bind(("127.0.0.1",0));s.listen();print(s.getsockname()[1],flush=True);time.sleep(30)' > "$TMP/port" & LSN=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do [ -s "$TMP/port" ] && break; sleep 0.2; done
: > "$DOCKER_LOG"; OUT="$(bash "$SCRIPT" start --port "$(cat "$TMP/port")" 2>&1)"; RC=$?; kill "$LSN" 2>/dev/null
check "busy port refused before docker run"    '[ "$RC" = 2 ] && printf "%s" "$OUT" | grep -q belegt && ! grep -q "^run " "$DOCKER_LOG"'
: > "$DOCKER_LOG"; OUT="$(RUN_FAILS=1 bash "$SCRIPT" start --port 1 2>&1)"; RC=$?
check "failed run leaves no container behind"  '[ "$RC" = 2 ] && grep -q "^rm -f mysuricates-tryout" "$DOCKER_LOG"'

echo "4) login, key, remove, export"
: > "$DOCKER_LOG"; EXISTS=1 bash "$SCRIPT" login codex >/dev/null 2>&1
check "login runs as the instance user with the volume home" 'grep -q -- "exec -it -u 1000 -e HOME=/app/data/home" "$DOCKER_LOG" && grep -q "codex login --device-auth" "$DOCKER_LOG"'
rm -f "$DOCKER_RAN"
check "login needs a running container"        'bash "$SCRIPT" login >/dev/null 2>&1; [ $? -eq 2 ]'
check "login rejects unknown tools"            'EXISTS=1 bash "$SCRIPT" login foo >/dev/null 2>&1; [ $? -eq 2 ]'
check "key reads the file on the volume"       '[ "$(EXISTS=1 bash "$SCRIPT" key)" = KEY123 ] && grep -q "sh -c .*/.autotrigger/api-key.*/.mysuricates/api-key.* sh /app/data/home$" "$DOCKER_LOG"'
: > "$DOCKER_LOG"; EXISTS=1 bash "$SCRIPT" remove >/dev/null 2>&1
check "remove keeps the data by default"       'grep -q "^rm -f mysuricates-tryout" "$DOCKER_LOG" && ! grep -q "^volume rm" "$DOCKER_LOG"'
: > "$DOCKER_LOG"; bash "$SCRIPT" remove --data >/dev/null 2>&1
check "remove --data deletes both volumes"     'grep -q "^volume rm mysuricates-tryout-data mysuricates-tryout-secrets" "$DOCKER_LOG"'
: > "$DOCKER_LOG"; (cd "$TMP" && bash "$SCRIPT" export >/dev/null 2>&1)
check "export saves both tags"                 'grep -q "^save mysuricates:tryout mysuricates:0.10.0-tryout" "$DOCKER_LOG"'
check "export file named after the version"    '[ -s "$TMP/mysuricates-0.10.0-tryout.tar.gz" ]'

echo "5) instance.sh leaves the tryout alone"
mkdir -p "$TMP/inst" && echo MYSURICATES_PORT=1 > "$TMP/inst/tryout.env"
OUT="$(MYSURICATES_INSTANCES_DIR="$TMP/inst" bash "$R/scripts/instance.sh" status --name tryout 2>&1)"; RC=$?
check "instance name tryout is reserved"       '[ "$RC" = 2 ] && printf "%s" "$OUT" | grep reserviert >/dev/null'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
