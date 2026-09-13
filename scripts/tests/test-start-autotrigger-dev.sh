#!/usr/bin/env bash
# Tests for scripts/start-autotrigger-dev.sh — JAR runtime handling and the restart run guard.
# Run: bash scripts/tests/test-start-autotrigger-dev.sh   (exit 0 = all passed)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT="$HERE/../start-autotrigger-dev.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

# Source the script's functions in a subshell with an isolated state dir and a fake repo.
# $1 = extra shell code to run after sourcing (evaluated inside the subshell).
run_in_script() {
  (
    export HOME="$TMP/home" XDG_STATE_HOME="$TMP/state"
    mkdir -p "$HOME" "$XDG_STATE_HOME"
    # shellcheck disable=SC1090
    source "$SCRIPT" >/dev/null 2>&1
    set +e
    REPO_ROOT="$TMP/repo"
    eval "$1"
  )
}

# Fake git repo so runtime_jar_label() has a sha and a clean/dirty state.
mkdir -p "$TMP/repo"
git -C "$TMP/repo" init -q
git -C "$TMP/repo" -c user.name=t -c user.email=t@t commit -q --allow-empty -m init
SHA="$(git -C "$TMP/repo" rev-parse --short HEAD)"

echo "1) runtime jar label"
LABEL="$(run_in_script 'runtime_jar_label')"
check "label carries the short sha ($SHA)"        '[[ "$LABEL" == *"-$SHA" ]]'
check "clean tree has no -dirty suffix"          '[[ "$LABEL" != *-dirty ]]'
echo "x" > "$TMP/repo/tracked.txt"; git -C "$TMP/repo" add tracked.txt; git -C "$TMP/repo" -c user.name=t -c user.email=t@t commit -q -m t
echo "y" > "$TMP/repo/tracked.txt"
LABEL_DIRTY="$(run_in_script 'runtime_jar_label')"
check "modified tracked file yields -dirty"      '[[ "$LABEL_DIRTY" == *-dirty ]]'
git -C "$TMP/repo" checkout -q -- tracked.txt

echo "2) install_runtime_jar: copy, symlink, rotation"
for i in 1 2 3 4; do echo "jar$i" > "$TMP/fake$i.jar"; done
OUT="$(run_in_script '
  for i in 1 2 3 4; do install_runtime_jar "'"$TMP"'/fake$i.jar" >/dev/null; sleep 1.1; done
  ls -1 "$RUNTIME_DIR"/backend-*.jar | wc -l
  cat "$RUNTIME_DIR/current.jar"
  readlink -f "$RUNTIME_DIR/current.jar"
')"
COUNT="$(sed -n 1p <<<"$OUT")"; CURRENT_CONTENT="$(sed -n 2p <<<"$OUT")"; CURRENT_TARGET="$(sed -n 3p <<<"$OUT")"
check "keeps only 2 jars after 4 installs (got $COUNT)"  '[ "$COUNT" -eq 2 ]'
check "current.jar points at the newest install"        '[ "$CURRENT_CONTENT" = "jar4" ]'
check "current.jar is a symlink into runtime dir"       '[[ "$CURRENT_TARGET" == *"/runtime/backend-"*".jar" ]]'
check "missing jar is rejected"                         '! run_in_script "install_runtime_jar /nonexistent.jar" >/dev/null 2>&1'

echo "3) restart guard against running workflow runs"
mkdir -p "$TMP/bin"
cat > "$TMP/bin/curl" <<'STUB'
#!/usr/bin/env bash
# Fake curl: answer the executions query with the JSON from $FAKE_RUNS_JSON, fail otherwise.
if [[ "$*" == *"/executions?status=RUNNING"* ]]; then printf '%s' "$FAKE_RUNS_JSON"; exit 0; fi
exit 22
STUB
chmod +x "$TMP/bin/curl"
guard() {  # $1 = json, $2 = FORCE
  run_in_script "export PATH=\"$TMP/bin:\$PATH\" MYSURICATES_API_KEY=k FAKE_RUNS_JSON='$1'; FORCE=$2; refuse_restart_if_runs_active >/dev/null 2>&1; echo \$?"
}
check "blocks (exit 3) when one run is active (page)"    '[ "$(guard "{\"content\":[{\"id\":\"r\"}],\"totalElements\":1}" 0)" = 3 ]'
check "blocks when runs come as a bare array"            '[ "$(guard "[{\"id\":\"r\"}]" 0)" = 3 ]'
check "passes when no run is active"                     '[ "$(guard "{\"content\":[],\"totalElements\":0}" 0)" = 0 ]'
check "--force overrides an active run"                  '[ "$(guard "{\"content\":[{\"id\":\"r\"}],\"totalElements\":1}" 1)" = 0 ]'
check "passes when the API is unreachable"               '[ "$(run_in_script "export PATH=\"$TMP/bin:\$PATH\" MYSURICATES_API_KEY=k MYSURICATES_API_BASE=http://x/api/v1 FAKE_RUNS_JSON=; API_BASE=http://x/api/v1; refuse_restart_if_runs_active >/dev/null 2>&1; echo \$?")" = 0 ]'
check "passes without any API key"                       '[ "$(run_in_script "unset MYSURICATES_API_KEY; refuse_restart_if_runs_active >/dev/null 2>&1; echo \$?")" = 0 ]'

echo "4) stale pid file handling"
OUT="$(run_in_script '
  echo 999999 > "$BACKEND_PID_FILE"
  discard_stale_pid_file "$BACKEND_PID_FILE" "$BACKEND_PROCESS_PATTERN" Backend >/dev/null
  [ -f "$BACKEND_PID_FILE" ] && echo kept || echo removed
')"
check "pid file of a dead process is removed"            '[ "$OUT" = removed ]'
OUT="$(run_in_script '
  sleep 30 & echo $! > "$BACKEND_PID_FILE"
  discard_stale_pid_file "$BACKEND_PID_FILE" "$BACKEND_PROCESS_PATTERN" Backend >/dev/null
  [ -f "$BACKEND_PID_FILE" ] && echo kept || echo removed
  kill %1 2>/dev/null
')"
check "pid file of a foreign process (sleep) is removed" '[ "$OUT" = removed ]'

echo "5) help and syntax"
check "bash -n passes"                                   'bash -n "$SCRIPT"'
check "--help prints usage without side effects"         '"$SCRIPT" --help | grep -q "restart"'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
