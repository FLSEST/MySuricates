#!/usr/bin/env bash
# Tests for scripts/lib/deps.sh — tools per mode, versions, package managers, verified downloads.
# Everything is stubbed: no package manager and no network is touched.
# Run: bash scripts/tests/test-deps.sh   (exit 0 = all passed)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$HERE/../lib"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }
mkdir -p "$TMP/bin" "$TMP/tools" "$TMP/home"
for t in uname sed awk grep cut tr head tail cat mkdir mktemp rm mv cp tar gzip ln chmod basename dirname sort id ls find git; do
  p="$(command -v "$t" 2>/dev/null)" && ln -sf "$p" "$TMP/tools/$t"
done
for t in sha256sum shasum curl; do p="$(command -v "$t" 2>/dev/null)" && ln -sf "$p" "$TMP/tools/$t"; done
stub() { printf '#!/bin/sh\n%s\n' "$2" > "$TMP/bin/$1"; chmod +x "$TMP/bin/$1"; }
unstub() { rm -f "$TMP/bin/$1"; }
run() { ( HOME="$TMP/home" PATH="$TMP/bin:$TMP/tools" XDG_DATA_HOME="$TMP/data"; export HOME PATH XDG_DATA_HOME; . "$LIB/deps.sh"; eval "$1" ) 2>&1; }

echo "1) versions"
stub java 'echo "openjdk version \"17.0.9\" 2023-10-17" >&2'
check "java 17 is too old"                   '[ "$(run "deps_check java")" = "old 17" ]'
stub java 'echo "openjdk version \"21.0.8\" 2025-07-15" >&2'
check "java 21 is fine"                      '[ "$(run "deps_check java")" = "ok 21" ]'
stub node 'echo v18.19.1';  check "node 18 is too old"      '[ "$(run "deps_check node")" = "old 18.19.1" ]'
stub node 'echo v23.4.0';   check "node 23 is excluded"     '[ "$(run "deps_check node")" = "old 23.4.0" ]'
stub node 'echo v22.12.0';  check "node 22.12 is fine"      '[ "$(run "deps_check node")" = "ok 22.12.0" ]'
stub node 'echo v24.21.0';  check "node 24 is fine"         '[ "$(run "deps_check node")" = "ok 24.21.0" ]'
check "missing tool is missing"              '[ "$(run "deps_check docker")" = "missing" ]'
stub docker 'case "$1" in --version) echo "Docker version 29.1.3, build x";; info) echo "permission denied while trying to connect" >&2; exit 1;; esac'
check "docker without rights"                '[ "$(run "deps_docker_state")" = "no-permission" ]'
stub docker 'case "$1" in --version) echo "Docker version 29.1.3, build x";; info) exit 0;; esac'
check "docker running"                       '[ "$(run "deps_docker_state")" = "running" ]'

echo "2) what each way needs"
check "container: git curl docker"           '[ "$(run "deps_required container")" = "git curl docker" ]'
check "host: git curl java node npm"         '[ "$(run "deps_required host")" = "git curl java node npm" ]'
check "source adds python3"                  'run "deps_required source" | grep -w python3 >/dev/null'

echo "3) package tables"
check "java on apt"                          '[ "$(run "deps_pkg java apt")" = "openjdk-21-jdk" ]'
check "java on dnf"                          '[ "$(run "deps_pkg java dnf")" = "java-21-openjdk-devel" ]'
check "java on pacman"                       '[ "$(run "deps_pkg java pacman")" = "jdk21-openjdk" ]'
check "java on brew"                         '[ "$(run "deps_pkg java brew")" = "openjdk@21" ]'
check "node brings npm on apt"               '[ "$(run "deps_pkg node apt")" = "nodejs npm" ]'
check "install command per manager"          '[ "$(run "deps_install_cmd dnf curl git")" = "sudo dnf install -y curl git" ] && [ "$(run "deps_install_cmd brew git")" = "brew install git" ]'

echo "4) plan: package manager where it fits, download where it does not"
stub apt-get 'exit 0'
stub apt-cache 'case "$2" in nodejs) printf "nodejs:\n  Installed: (none)\n  Candidate: 18.19.1+dfsg-6ubuntu5\n";; openjdk-21-jdk) printf "openjdk-21-jdk:\n  Candidate: 21.0.8+9-1\n";; *) printf "%s:\n  Candidate: 1.0\n" "$2";; esac'
unstub java; unstub node; stub npm 'echo 9.2.0'
PLAN="$(run "deps_plan host")"
check "java via apt (candidate fits)"        'printf "%s\n" "$PLAN" | grep -E "^java +pm +sudo apt-get install -y openjdk-21-jdk" >/dev/null'
check "node via download (apt has 18)"       'printf "%s\n" "$PLAN" | grep -E "^node +download " >/dev/null'
check "npm comes with the downloaded node"   '! printf "%s\n" "$PLAN" | grep -E "^npm " >/dev/null'
PRESENT=""; for t in git curl; do [ -x "$TMP/tools/$t" ] && PRESENT="$PRESENT|$t"; done
check "present tools are not in the plan"    '[ -z "$PRESENT" ] || ! printf "%s\n" "$PLAN" | grep -E "^(${PRESENT#|}) " >/dev/null'

echo "5) verified download"
if [ -x "$TMP/tools/curl" ]; then
  mkdir -p "$TMP/pkg/node-v9.9.9-linux-x64/bin"
  printf '#!/bin/sh\necho v9.9.9\n' > "$TMP/pkg/node-v9.9.9-linux-x64/bin/node"; chmod +x "$TMP/pkg/node-v9.9.9-linux-x64/bin/node"
  (cd "$TMP/pkg" && tar czf node.tgz node-v9.9.9-linux-x64)
  SUM="$( (sha256sum "$TMP/pkg/node.tgz" 2>/dev/null || shasum -a 256 "$TMP/pkg/node.tgz") | cut -d' ' -f1)"
  OUT="$(run "DEPS_NODE_URL=file://$TMP/pkg/node.tgz DEPS_NODE_SHA256=$SUM deps_download node; echo rc=\$?")"
  check "download with the right checksum"     'printf "%s" "$OUT" | grep "rc=0" >/dev/null && [ -x "$TMP/data/mysuricates/node/bin/node" ]'
  check "env.sh puts it on the PATH"           'grep "mysuricates/node/bin" "$TMP/data/mysuricates/env.sh" >/dev/null'
  rm -rf "$TMP/data"
  OUT="$(run "DEPS_NODE_URL=file://$TMP/pkg/node.tgz DEPS_NODE_SHA256=0000000000000000000000000000000000000000000000000000000000000000 deps_download node; echo rc=\$?")"
  check "wrong checksum aborts, nothing installed" '! printf "%s" "$OUT" | grep "rc=0" >/dev/null && [ ! -e "$TMP/data/mysuricates/node" ]'
else
  echo "  skip (no curl in this environment — setup.sh installs curl first)"
fi
check "pinned checksums are 64 hex chars"    '[ "$(grep -cE "^DEPS_(NODE|JDK)_SHA256_[a-z0-9_]+=\"[0-9a-f]{64}\"" "$LIB/deps.sh")" -eq 8 ]'

echo "6) bash 3.2 hygiene"
check "no bash-4-only syntax (code)"         '! grep -v "^[[:space:]]*#" "$LIB/deps.sh" | grep -nE "declare -g?A|mapfile|readarray|\\$\\{[A-Za-z_]+(,,|\\^\\^)\\}" >/dev/null'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
