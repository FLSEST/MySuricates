#!/usr/bin/env bash
# Tests for scripts/setup.sh — the guided installer. Without a terminal, with --dry-run and stubbed
# tools: nothing is installed, built or started; only the decisions and the written files count.
# Run: bash scripts/tests/test-setup.sh   (exit 0 = all passed)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETUP="$HERE/../setup.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }
export LC_ALL="${LC_ALL:-C.UTF-8}"

# Own PATH: stub tools plus only the system's basic tools (no real AI CLIs)
mkdir -p "$TMP/bin" "$TMP/tools" "$TMP/home"
for t in bash uname sed awk grep cut tr head tail cat mkdir mktemp rm mv cp tar ln chmod basename dirname sort id ls find wc env sleep comm printf; do
  p="$(command -v "$t" 2>/dev/null)" && [ -x "$p" ] && ln -sf "$p" "$TMP/tools/$t"
done
stub() { printf '#!/bin/sh\n%s\n' "$2" > "$TMP/bin/$1"; chmod +x "$TMP/bin/$1"; }
unstub() { rm -f "$TMP/bin/$1"; }
stub git 'echo "git version 2.43.0"'
stub curl 'case "$1" in --version) echo "curl 8.5.0 (x86_64)";; *) exit 7;; esac'
stub java 'echo "openjdk version \"21.0.8\" 2025-07-15" >&2'
stub node 'echo v24.21.0'
# npm: version, global directory (NPM_PREFIX) and `install` (NPM_LOG; creates NPM_CREATES as a program)
NPM_STUB='case "$1" in prefix) echo "${NPM_PREFIX:-/nonexistent-npm-prefix}" ;; install) echo "$*" >> "${NPM_LOG:-/dev/null}"; [ -z "${NPM_CREATES:-}" ] || { printf "#!/bin/sh\nexit 0\n" > "$NPM_CREATES"; chmod +x "$NPM_CREATES"; } ;; *) echo 11.19.0 ;; esac'
stub npm "$NPM_STUB"
stub docker 'case "$1" in --version) echo "Docker version 29.1.3, build x";; info) exit 0;; esac'
stub apt-get 'exit 0'
stub codex 'exit 0'

INST="$TMP/inst"
# XDG_CONFIG_HOME has to be set too: setup.sh:35 resolves AI_ENV via
# ${XDG_CONFIG_HOME:-$HOME/.config}. If the run inherits the variable, the tests write
# to $TMP/home/.config and setup.sh reads elsewhere — the token is never found. On
# the CI runner it is set, locally mostly not (run 35912021976: exactly these 2 red).
run() {  # setup.sh without a terminal; stdin closed
  ( HOME="$TMP/home" PATH="$TMP/bin:$TMP/tools" XDG_DATA_HOME="$TMP/data" XDG_STATE_HOME="$TMP/state" \
    XDG_CONFIG_HOME="$TMP/home/.config" \
    MYSURICATES_INSTANCES_DIR="$INST" TERM=dumb; export HOME PATH XDG_DATA_HOME XDG_STATE_HOME XDG_CONFIG_HOME MYSURICATES_INSTANCES_DIR TERM
    bash "$SETUP" "$@" < /dev/null ) > "$TMP/out" 2>&1
  echo $?
}
val() { grep "^$2=" "$INST/$1.env" 2>/dev/null | cut -d= -f2-; }
# First free port from BASE — the installer skips occupied ports (a dev instance may be running here)
firstfree() { local p="$1" taken=" ${2:-} "; while (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null || [ "${taken#* $p }" != "$taken" ]; do p=$((p + 1)); done; echo "$p"; }
out() { cat "$TMP/out"; }

echo "1) flags and compatibility"
check "bash -n"                                         'bash -n "$SETUP"'
check "--help names the new ways"                       'bash "$SETUP" --help | grep -- "--runtime container|host" >/dev/null && bash "$SETUP" --help | grep -- "--mode one|staged" >/dev/null'
check "--help documents --ai-from-host and --ai-providers" 'bash "$SETUP" --help | grep -- "--ai-from-host" >/dev/null && bash "$SETUP" --help --lang de | grep -- "--ai-providers" >/dev/null'
check "--help still documents --preset core, --lang de, --force" 'H="$(bash "$SETUP" --help)"; [[ "$H" == *"--preset core"* && "$H" == *"--lang de"* && "$H" == *"--force"* ]]'
check "unknown preset exits 2"                          '[ "$(run --preset everything)" = 2 ]'
check "--force without --preset exits 2 and says why"   '[ "$(run --force)" = 2 ] && out | grep -- "--preset" >/dev/null'
check "unknown runtime exits 2"                         '[ "$(run --runtime moon)" = 2 ]'
check "unknown module exits 2"                          '[ "$(run --yes --dry-run --modules nope)" = 2 ] && out | grep nope >/dev/null'
check "--help is English by default"                    'bash "$SETUP" --help | sed -n 1p | grep "from a bare machine" >/dev/null'
check "--help --lang de is German"                      'bash "$SETUP" --help --lang de | sed -n 1p | grep "nackten Maschine" >/dev/null'
check "argument errors follow the language"             '[ "$(run --lang de --mode x)" = 2 ] && out | grep "nicht" >/dev/null && [ "$(run --mode x)" = 2 ] && out | grep "not" >/dev/null'

echo "2) check only (no terminal, no --yes): nothing is written"
check "all present → exit 0"                            '[ "$(run --runtime host)" = 0 ] && [ ! -e "$INST/main.env" ]'
unstub java
check "java missing → exit 1 and the plan names it"     '[ "$(run --check --runtime host)" = 1 ] && out | grep -E "^ *java" >/dev/null'
stub java 'echo "openjdk version \"21.0.8\" 2025-07-15" >&2'
# Rocky Linux without Docker: Docker's Rocky repo has no docker-ce — the plan takes the CentOS repo (24515f13)
unstub docker; unstub apt-get; stub dnf 'exit 0'
printf 'ID="rocky"\nID_LIKE="rhel centos fedora"\n' > "$TMP/os-release-rocky"
check "rocky without docker → plan installs it from Docker's CentOS repo" '[ "$(DEPS_OS_RELEASE="$TMP/os-release-rocky" run --check)" = 1 ] && out | grep -E "^docker +pm +.*linux/centos/docker-ce.repo" >/dev/null && ! out | grep -F get.docker.com >/dev/null'
unstub dnf; stub apt-get 'exit 0'
stub docker 'case "$1" in --version) echo "Docker version 29.1.3, build x";; info) exit 0;; esac'

echo "3) one instance on this machine"
rm -rf "$INST"
check "--yes --dry-run --runtime host → exit 0"         '[ "$(run --yes --dry-run --runtime host)" = 0 ]'
check "instance file for host"                          '[ "$(val main MYSURICATES_RUNTIME)" = host ] && [ "$(val main MYSURICATES_PORT)" = "$(firstfree 9090)" ] && [ "$(val main MYSURICATES_PRESET)" = core ]'
check "default provider is the one installed (codex)"   '[ "$(val main AUTOMATION_AI_DEFAULT_PROVIDER)" = CODEX ]'
check "dry run names the start command"                 'out | grep "instance.sh up --name main" >/dev/null'
check "host: no container switches for the AI"          '[ -z "$(val main MYSURICATES_AI_FROM_HOST)" ]'
check "host: says it does not start after a reboot"     'out | grep -F "After a reboot of this machine main does not start by itself — then: scripts/instance.sh up --name main" >/dev/null'

echo "4) one instance in a container (default)"
rm -rf "$INST"
check "--yes --dry-run → container"                     '[ "$(run --yes --dry-run)" = 0 ] && [ "$(val main MYSURICATES_RUNTIME)" = container ]'
check "projects: folder above the clone"                '[ "$(val main MYSURICATES_PROJECTS)" = "$(cd "$HERE/../../.." && pwd)" ]'
check "docker socket off by default"                    '[ "$(val main MYSURICATES_DOCKER_SOCKET)" = 0 ]'
check "container: no reboot warning (restarts with Docker)" '! out | grep "does not start by itself" >/dev/null'
check "one instance: no shared network, no sync targets" '[ -z "$(val main MYSURICATES_NETWORK)" ] && ! grep -q "SYNC" "$INST/main.env" && ! out | grep -F "sync-keys" >/dev/null'
rm -rf "$INST"
check "--docker-socket mounts it on request"            '[ "$(run --yes --dry-run --docker-socket)" = 0 ] && [ "$(val main MYSURICATES_DOCKER_SOCKET)" = 1 ]'

echo "4b) what of the AI providers goes into the container (262948a4)"
rm -rf "$INST"
check "default: only the login, all providers"          '[ "$(run --yes --dry-run)" = 0 ] && [ "$(val main MYSURICATES_AI_FROM_HOST)" = login ] && [ -z "$(val main MYSURICATES_AI_PROVIDERS)" ]'
rm -rf "$INST"
check "--ai-from-host / --ai-providers are written"     '[ "$(run --yes --dry-run --ai-from-host programs --ai-providers "Codex, pi")" = 0 ] && [ "$(val main MYSURICATES_AI_FROM_HOST)" = programs ] && [ "$(val main MYSURICATES_AI_PROVIDERS)" = codex,pi ]'
check "unknown --ai-from-host exits 2"                  '[ "$(run --yes --dry-run --ai-from-host everything)" = 2 ] && out | grep -- "--ai-from-host" >/dev/null'
check "unknown --ai-providers exits 2"                  '[ "$(run --yes --dry-run --ai-providers gemini)" = 2 ] && out | grep gemini >/dev/null'
stub claude 'exit 0'
rm -rf "$INST"
check "claude without token: hint, nothing written"     '[ "$(run --yes --dry-run)" = 0 ] && out | grep -F "claude setup-token" >/dev/null && [ ! -e "$TMP/home/.config/mysuricates/ai.env" ]'
rm -rf "$INST"
check "no hint when claude is not selected"             '[ "$(run --yes --dry-run --ai-providers codex)" = 0 ] && ! out | grep -F "claude setup-token" >/dev/null'
mkdir -p "$TMP/home/.config/mysuricates"; printf 'CLAUDE_CODE_OAUTH_TOKEN=secret-test-token-0123456789-abcdefghijklmnop\n' > "$TMP/home/.config/mysuricates/ai.env"
rm -rf "$INST"
check "claude with a token: no hint"                    '[ "$(run --yes --dry-run)" = 0 ] && ! out | grep -F "claude setup-token" >/dev/null'
printf 'CLAUDE_CODE_OAUTH_TOKEN=<token>\n' > "$TMP/home/.config/mysuricates/ai.env"
rm -rf "$INST"
check "placeholder token: named, hint, file kept"       '[ "$(run --yes --dry-run)" = 0 ] && out | grep -E "CLAUDE_CODE_OAUTH_TOKEN.*40" >/dev/null && out | grep -F "claude setup-token" >/dev/null && grep -qxF "CLAUDE_CODE_OAUTH_TOKEN=<token>" "$TMP/home/.config/mysuricates/ai.env"'
check "a pasted token is checked before it is saved"    'grep -F "claude_token_plausible \"\$token\"" "$SETUP" >/dev/null'
rm -rf "$TMP/home/.config"; unstub claude

echo "4c) pi only on request; the downloaded Node in the user's shell (0a078449, 2605ed1f)"
export NPM_LOG="$TMP/npm.log"; : > "$NPM_LOG"
OLD_SHELL="${SHELL:-}"; export SHELL=/bin/bash   # profile: ~/.profile (without ~/.bash_profile)
# The command from the catalog, in a fixed version (c3a4cbf6) — the version is only stated there
PI_NPM="$(. "$HERE/../lib/provider-catalog.generated.sh"; printf '%s' "${PROVIDER__pi__install#npm }")"
check "pi comes in a pinned version"                    'printf "%s" "$PI_NPM" | grep -E "^install -g @earendil-works/pi-coding-agent@[0-9]+\.[0-9]+\.[0-9]+$" >/dev/null'
rm -rf "$INST"
check "default: no pi, also with --yes"                 '[ "$(run --yes --dry-run --runtime host)" = 0 ] && ! out | grep "pi-coding-agent" >/dev/null && [ ! -s "$NPM_LOG" ]'
rm -rf "$INST"
check "--with-pi: npm -g with sudo where root owns it"  '[ "$(run --yes --dry-run --runtime host --with-pi)" = 0 ] && out | grep -F "would run: sudo npm $PI_NPM" >/dev/null'
mkdir -p "$TMP/npmprefix"; rm -rf "$INST"
check "--with-pi: without sudo where npm may write"     '[ "$(NPM_PREFIX="$TMP/npmprefix" run --yes --dry-run --runtime host --with-pi)" = 0 ] && out | grep -F "would run: npm $PI_NPM" >/dev/null && ! out | grep -F "sudo npm" >/dev/null'
rm -rf "$INST"
check "--with-pi installs pi, nothing started"          '[ "$(NPM_PREFIX="$TMP/npmprefix" NPM_CREATES="$TMP/bin/pi" run --install --runtime host --with-pi)" = 0 ] && grep -qxF "$PI_NPM" "$NPM_LOG" && out | grep -E "Install pi \(npm\)" >/dev/null && [ -x "$TMP/bin/pi" ] && [ -f "$INST/main.env" ]'
unstub pi; unstub npm; rm -rf "$INST"
check "--with-pi without npm: says so, carries on"     '[ "$(run --yes --dry-run --runtime container --with-pi)" = 0 ] && out | grep -- "--with-pi needs npm" >/dev/null'
stub npm "$NPM_STUB"
check "--help names --with-pi"                          'bash "$SETUP" --help | grep -- "--with-pi" >/dev/null && bash "$SETUP" --help --lang de | grep -- "--with-pi" >/dev/null'
# Downloaded Node: env.sh puts it on the PATH, the user's shell does not know it
DD="$TMP/data/mysuricates"; mkdir -p "$DD/node/bin"
printf 'export PATH="%s/node/bin:$PATH"\n' "$DD" > "$DD/env.sh"
rm -rf "$INST"
check "--yes: profile untouched, the line is shown"    '[ "$(run --yes --dry-run --runtime host)" = 0 ] && out | grep -F "only with this line in ~/.profile: . $DD/env.sh" >/dev/null && [ ! -e "$TMP/home/.profile" ]'
printf '#!/bin/sh\n%s\n' "$NPM_STUB" > "$DD/node/bin/npm"; chmod +x "$DD/node/bin/npm"; unstub codex; rm -rf "$INST"
CODEX_NPM="$(. "$HERE/../lib/provider-catalog.generated.sh"; printf '%s' "$PROVIDER__codex__install")"   # pinned (38f30352)
check "codex comes in a pinned version"                 'printf "%s" "$CODEX_NPM" | grep -E "^npm install -g @openai/codex@[0-9]+\.[0-9]+\.[0-9]+$" >/dev/null'
check "no CLI yet: full npm commands with env.sh first" '[ "$(run --yes --dry-run --runtime host)" = 0 ] && out | grep -F "install: . $DD/env.sh && $CODEX_NPM   log in: . $DD/env.sh && codex login" >/dev/null'
check "the commands are not cut off by the box"         '! out | grep -E "install: .*…" >/dev/null'
stub codex 'exit 0'
pty_profile() {  # KEYS → recording in $TMP/menu; without --dry-run, nothing is built (n)
  printf "$1" | script -qec "stty cols 120 rows 50; env HOME='$TMP/home' SHELL=/bin/bash PATH='$TMP/bin:$TMP/tools' XDG_DATA_HOME='$TMP/data' XDG_STATE_HOME='$TMP/state' XDG_CONFIG_HOME='$TMP/home/.config' MYSURICATES_INSTANCES_DIR='$INST' TERM=xterm-256color bash '$SETUP' --lang en --runtime host --preset core --provider codex --port 19490" /dev/null > "$TMP/menu" 2>&1
}
if command -v script >/dev/null 2>&1 && script -qec true /dev/null >/dev/null 2>&1; then
  rm -rf "$INST"; pty_profile 'n\ny\nn\n'   # pi: no · profile: yes · build: no
  check "guided: the line goes into ~/.profile once"   '[ "$(grep -c "mysuricates/env.sh" "$TMP/home/.profile" 2>/dev/null)" = 1 ] && grep -qxF "[ -r \"$DD/env.sh\" ] && . \"$DD/env.sh\"" "$TMP/home/.profile"'
  pty_profile 'n\nn\n'
  check "guided again: not asked, not added twice"     '[ "$(grep -c "mysuricates/env.sh" "$TMP/home/.profile")" = 1 ] && ! grep -q "Add one line" "$TMP/menu"'
else
  echo "  skip (no usable script command)"
fi
rm -rf "$DD" "$TMP/home/.profile" "$INST"
if [ -n "$OLD_SHELL" ]; then export SHELL="$OLD_SHELL"; else unset SHELL; fi

echo "4d) logged in only via a variable: the container does not get it (90b4422c)"
LOGIN_VARS="CLAUDE_CODE_OAUTH_TOKEN ANTHROPIC_API_KEY ANTHROPIC_OAUTH_TOKEN OPENAI_API_KEY CODEX_API_KEY GEMINI_API_KEY OPENROUTER_API_KEY"
runvars() {  # "VAR=VALUE …" FLAGS… — only these login variables, none of the machine's
  local kv="$1"; shift
  ( for v in $LOGIN_VARS; do unset "$v"; done; for a in $kv; do export "$a"; done; run "$@" )
}
rm -rf "$INST"
AIE="$TMP/home/.config/mysuricates/ai.env"
mode() { stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"; }
check "codex only via OPENAI_API_KEY: says how it gets in" '[ "$(runvars OPENAI_API_KEY=sk-test-geheim-42 --yes --dry-run)" = 0 ] && out | grep -F "codex is logged in here only via OPENAI_API_KEY — the variable does not reach the container by itself. With --store-ai-env (or yes in the terminal) the installer copies it into $AIE; otherwise log in on this machine: codex login" >/dev/null'
check "the value is never shown"                          '! out | grep "sk-test-geheim-42" >/dev/null'
check "--yes alone never writes a secret"                 '[ ! -e "$AIE" ]'
rm -rf "$INST"
check "--store-ai-env: copied, value not shown"           '[ "$(runvars OPENAI_API_KEY=sk-test-geheim-42 --yes --dry-run --store-ai-env)" = 0 ] && out | grep -F "OPENAI_API_KEY saved in $AIE" >/dev/null && ! out | grep "sk-test-geheim-42" >/dev/null'
check "exactly that variable, file 600, directory 700"   '[ "$(cat "$AIE")" = "OPENAI_API_KEY=sk-test-geheim-42" ] && [ "$(mode "$AIE")" = 600 ] && [ "$(mode "$(dirname "$AIE")")" = 700 ]'
rm -rf "$INST"
check "already in ai.env: not asked, not rewritten"       '[ "$(runvars OPENAI_API_KEY=sk-test-other-43 --yes --dry-run --store-ai-env)" = 0 ] && ! out | grep "only via" >/dev/null && ! out | grep "saved in" >/dev/null && grep -qx "OPENAI_API_KEY=sk-test-geheim-42" "$AIE"'
printf '%s\n' 'CLAUDE_CODE_OAUTH_TOKEN=secret-test-token-0123456789-abcdefghijklmnop' 'OPENAI_API_KEY=' 'KEEP_ME=1' > "$AIE"; rm -rf "$INST"
check "others kept, the empty line replaced"              '[ "$(runvars OPENAI_API_KEY=sk-test-geheim-42 --yes --dry-run --store-ai-env --ai-providers codex)" = 0 ] && [ "$(grep -c "^OPENAI_API_KEY=" "$AIE")" = 1 ] && grep -qx "OPENAI_API_KEY=sk-test-geheim-42" "$AIE" && grep -qx "KEEP_ME=1" "$AIE" && grep -qx "CLAUDE_CODE_OAUTH_TOKEN=secret-test-token-0123456789-abcdefghijklmnop" "$AIE" && [ "$(mode "$AIE")" = 600 ]'
rm -rf "$TMP/home/.config" "$INST"; stub pi 'exit 0'
check "shared by codex and pi: stored once"               '[ "$(runvars "OPENAI_API_KEY=sk-test-geheim-42" --yes --dry-run --store-ai-env --ai-providers codex,pi)" = 0 ] && [ "$(grep -c "^OPENAI_API_KEY=" "$AIE")" = 1 ] && [ "$(out | grep -c "saved in")" = 1 ]'
rm -rf "$TMP/home/.config" "$INST"
check "ANTHROPIC_API_KEY for pi: says claude uses it too" '[ "$(runvars "ANTHROPIC_API_KEY=sk-test-geheim-44" --yes --dry-run --store-ai-env --ai-providers claude,pi)" = 0 ] && grep -qx "ANTHROPIC_API_KEY=sk-test-geheim-44" "$AIE" && out | grep -F "claude reads ANTHROPIC_API_KEY as well" >/dev/null && ! out | grep "sk-test-geheim-44" >/dev/null'
rm -rf "$TMP/home/.config" "$INST"
check "… not when claude is not selected"                 '[ "$(runvars "ANTHROPIC_API_KEY=sk-test-geheim-44" --yes --dry-run --store-ai-env --ai-providers pi)" = 0 ] && ! out | grep "claude reads" >/dev/null'
unstub pi; rm -rf "$TMP/home/.config" "$INST"
check "all: no copy, the old hint"                        '[ "$(runvars OPENAI_API_KEY=sk-test-geheim-42 --yes --dry-run --store-ai-env --ai-from-host all)" = 0 ] && [ ! -e "$AIE" ] && out | grep -F "the variable does not reach the container. For the container, log in on this machine: codex login" >/dev/null'
# In a terminal: asked, default no — only an explicit yes writes
pty_store() {  # KEYS → recording in $TMP/menu (ANSI stripped); only OPENAI_API_KEY as login variable
  local unset_vars="" v
  for v in $LOGIN_VARS; do unset_vars="$unset_vars -u $v"; done
  rm -rf "$INST"
  printf "$1" | timeout 120 script -qec "stty cols 120 rows 50; env $unset_vars HOME='$TMP/home' PATH='$TMP/bin:$TMP/tools' XDG_DATA_HOME='$TMP/data' XDG_STATE_HOME='$TMP/state' XDG_CONFIG_HOME='$TMP/home/.config' MYSURICATES_INSTANCES_DIR='$INST' TERM=xterm-256color OPENAI_API_KEY=sk-test-geheim-42 bash '$SETUP' --dry-run --lang en --runtime container --preset core --provider codex --port 19490" /dev/null 2>&1 \
    | sed "s/$(printf "\033")\[[0-9;?]*[A-Za-z]//g" | tr -d "\r" > "$TMP/menu"
}
if command -v script >/dev/null 2>&1 && script -qec true /dev/null >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1; then
  pty_store 'n\n\n\nn\n'   # pi: no · access: login · adopt: Enter (= no) · build: no
  check "guided: asked, default no, nothing written"      'grep -qF "Copy it into $AIE for the container" "$TMP/menu" && grep -qF "[y/N]" "$TMP/menu" && [ ! -e "$AIE" ] && ! grep -q "sk-test-geheim-42" "$TMP/menu"'
  pty_store 'n\n\ny\nn\n'
  check "guided: yes writes exactly the variable, 600"    '[ "$(cat "$AIE" 2>/dev/null)" = "OPENAI_API_KEY=sk-test-geheim-42" ] && [ "$(mode "$AIE")" = 600 ] && ! grep -q "sk-test-geheim-42" "$TMP/menu"'
  rm -rf "$TMP/home/.config"
else
  echo "  skip (no usable script command)"
fi
rm -rf "$INST"
check "--help names --store-ai-env"                       'bash "$SETUP" --help | grep -- "--store-ai-env" >/dev/null && bash "$SETUP" --help --lang de | grep -- "--store-ai-env" >/dev/null'
mkdir -p "$TMP/home/.codex"; echo '{"tokens":1}' > "$TMP/home/.codex/auth.json"; rm -rf "$INST"
check "with ~/.codex/auth.json: no such hint"             '[ "$(runvars OPENAI_API_KEY=sk-test-geheim-42 --yes --dry-run)" = 0 ] && ! out | grep "only via" >/dev/null'
rm -rf "$TMP/home/.codex" "$INST"
check "programs: every instance logs in itself, no hint"  '[ "$(runvars OPENAI_API_KEY=x --yes --dry-run --ai-from-host programs --store-ai-env)" = 0 ] && ! out | grep "only via" >/dev/null && [ ! -e "$AIE" ]'
rm -rf "$INST"
check "on this machine the variable just works: no hint"  '[ "$(runvars OPENAI_API_KEY=x --yes --dry-run --runtime host --store-ai-env)" = 0 ] && ! out | grep "only via" >/dev/null && [ ! -e "$AIE" ]'
rm -rf "$INST"

echo "5) staged — Dev · Test · Prod"
rm -rf "$INST"
check "--mode staged → exit 0"                          '[ "$(run --yes --dry-run --mode staged)" = 0 ]'
D="$(firstfree 9090)"; T="$(firstfree 8090 "$D")"; P="$(firstfree 9091 "$D $T")"
check "three instances from 9090 / 8090 / 9091 on"     '[ "$(val dev MYSURICATES_PORT)" = "$D" ] && [ "$(val test MYSURICATES_PORT)" = "$T" ] && [ "$(val prod MYSURICATES_PORT)" = "$P" ]'
check "no two instances on one port"                    '[ "$(for n in dev test prod; do val $n MYSURICATES_PORT; done | sort -u | wc -l | tr -d " ")" = 3 ]'
check "dev has all modules, prod is hardened"           '[ "$(val dev MYSURICATES_PRESET)" = full ] && [ "$(val prod MYSURICATES_PRESET)" = core ] && [ "$(val prod MYSURICATES_HARDENING_SEALING)" = true ] && [ "$(val prod MYSURICATES_HARDENING_REQUIRE_SEALED)" = true ]'
check "test is like prod"                               '[ "$(val test MYSURICATES_PRESET)" = core ] && [ "$(val test MYSURICATES_HARDENING_SEALING)" = true ]'
check "display names Dev / Test / Prod"                 '[ "$(val prod MYSURICATES_ENVIRONMENT)" = Prod ] && [ "$(val dev MYSURICATES_ENVIRONMENT)" = Dev ]'
check "staged runs in containers"                       '[ "$(val test MYSURICATES_RUNTIME)" = container ]'
check "staged: only the login in all three"             '[ "$(val dev MYSURICATES_AI_FROM_HOST)" = login ] && [ "$(val prod MYSURICATES_AI_FROM_HOST)" = login ]'
# Definition sync Dev → Test / Prod (84a78bb7): shared network, targets under their instance name, keys from files
check "staged: all three in one network"                '[ "$(val dev MYSURICATES_NETWORK)" = staged ] && [ "$(val test MYSURICATES_NETWORK)" = staged ] && [ "$(val prod MYSURICATES_NETWORK)" = staged ]'
check "staged: dev knows test and prod (URL + key file)" '[ "$(val dev MYSURICATES_SYNC_TARGETS)" = test,prod ] && [ "$(val dev AUTOMATION_SYNC_TEST_URL)" = http://test:9090 ] && [ "$(val dev AUTOMATION_SYNC_PROD_URL)" = http://prod:9090 ] && [ "$(val dev AUTOMATION_SYNC_TEST_KEY_FILE)" = /app/sync-keys/test.key ] && [ "$(val dev AUTOMATION_SYNC_PROD_KEY_FILE)" = /app/sync-keys/prod.key ]'
check "staged: only dev has sync targets"               '[ -z "$(val test MYSURICATES_SYNC_TARGETS)" ] && [ -z "$(val prod AUTOMATION_SYNC_PROD_URL)" ]'
check "staged: dev seals (prod takes only sealed), runs unattended unsealed" '[ "$(val dev MYSURICATES_HARDENING_SEALING)" = true ] && [ "$(val dev MYSURICATES_HARDENING_REQUIRE_SEALED)" = false ]'
check "staged: no key in any file, no host.docker.internal" '! grep -il "api.key=\|host.docker.internal" "$INST"/dev.env "$INST"/test.env "$INST"/prod.env >/dev/null'
check "staged dry run names the key fetch"              'out | grep -F "scripts/instance.sh sync-keys --name dev" >/dev/null'
# Kept files from before the sync: missing lines are added, existing ones stay
sed -i.bak "/^MYSURICATES_NETWORK=/d; /^MYSURICATES_SYNC_TARGETS=/d; /^AUTOMATION_SYNC_/d; /Definition-Sync/d" "$INST/dev.env" "$INST/test.env"; rm -f "$INST"/*.bak
printf 'AUTOMATION_SYNC_PROD_URL=http://elsewhere:9090\n' >> "$INST/dev.env"
check "kept files get the sync lines, own values stay"  '[ "$(run --yes --dry-run --mode staged)" = 0 ] && [ "$(val test MYSURICATES_NETWORK)" = staged ] && [ "$(val dev AUTOMATION_SYNC_TEST_URL)" = http://test:9090 ] && [ "$(val dev AUTOMATION_SYNC_PROD_URL)" = http://elsewhere:9090 ] && [ "$(grep -c "^AUTOMATION_SYNC_PROD_URL=" "$INST/dev.env")" = 1 ]'
check "a second run adds nothing twice"                 '[ "$(run --yes --dry-run --mode staged)" = 0 ] && [ "$(grep -c "^MYSURICATES_NETWORK=" "$INST/dev.env")" = 1 ] && [ "$(grep -c "Definition-Sync" "$INST/dev.env")" = 1 ]'
check "new staged setup: no sealing warning"            '! out | grep -F "Dev does not seal" >/dev/null'
# dev.env from before the sync: does not seal — hint on how prod sync gets to seals; --reconfigure switches over
sed -i.bak "s/^MYSURICATES_HARDENING_SEALING=.*/MYSURICATES_HARDENING_SEALING=false/" "$INST/dev.env"; rm -f "$INST"/*.bak
check "kept dev without seals: says Prod needs them and how" '[ "$(run --yes --dry-run --mode staged)" = 0 ] && out | grep -F "Dev does not seal (MYSURICATES_HARDENING_SEALING=false in deploy/instances/dev.env)" >/dev/null && out | grep -F "scripts/setup.sh --mode staged --reconfigure" >/dev/null && [ "$(val dev MYSURICATES_HARDENING_SEALING)" = false ]'
check "--reconfigure: dev seals now, and says so"       '[ "$(run --yes --dry-run --mode staged --reconfigure)" = 0 ] && [ "$(val dev MYSURICATES_HARDENING_SEALING)" = true ] && out | grep -F "Dev seals now" >/dev/null && ! out | grep -F "Dev does not seal" >/dev/null && [ "$(val dev AUTOMATION_SYNC_PROD_URL)" = http://prod:9090 ]'
rm -rf "$INST"
D="$(firstfree 19750)"; T="$(firstfree 19751 "$D")"; P="$(firstfree 19752 "$D $T")"
check "staged --port N: Dev from N, Test N+1, Prod N+2"  '[ "$(run --yes --dry-run --mode staged --port 19750)" = 0 ] && [ "$(val dev MYSURICATES_PORT)" = "$D" ] && [ "$(val test MYSURICATES_PORT)" = "$T" ] && [ "$(val prod MYSURICATES_PORT)" = "$P" ]'

echo "6) choices as flags"
rm -rf "$INST"
check "preset, modules, provider, port"                 '[ "$(run --yes --dry-run --runtime host --preset research --modules webhooks,terminal --provider pi --port 9555)" = 0 ]'
check "preset research"                                 '[ "$(val main MYSURICATES_PRESET)" = research ]'
check "single modules as AUTOMATION_ lines"             '[ "$(val main AUTOMATION_MODULES_WEBHOOKS)" = true ] && [ "$(val main AUTOMATION_MODULES_TERMINAL)" = true ]'
check "provider pi"                                     '[ "$(val main AUTOMATION_AI_DEFAULT_PROVIDER)" = PI ]'
check "port 9555"                                       '[ "$(val main MYSURICATES_PORT)" = 9555 ]'

rm -rf "$INST"
check "--bind 0.0.0.0 for access from the network" '[ "$(run --yes --dry-run --runtime host --bind 0.0.0.0)" = 0 ] && [ "$(val main MYSURICATES_BIND)" = 0.0.0.0 ]'
check "bound to 127.0.0.1 by default"             'rm -rf "$INST"; [ "$(run --yes --dry-run --runtime host)" = 0 ] && [ "$(val main MYSURICATES_BIND)" = 127.0.0.1 ]'
check "--bind rejects non-addresses"              '[ "$(run --bind "x;y")" = 2 ]'

echo "6a) protection of unattended runs (6ee57b87)"
rm -rf "$INST"
check "unknown --protection exits 2"                    '[ "$(run --yes --dry-run --protection moon)" = 2 ] && out | grep -- "--protection" >/dev/null'
check "without the flag nothing is switched"            '[ "$(run --yes --dry-run --runtime host)" = 0 ] && [ -z "$(val main AUTOMATION_HARDENING_UNATTENDEDINCONTAINER)" ] && [ -z "$(val main AUTOMATION_HARDENING_UNATTENDEDSANDBOX)" ]'
rm -rf "$INST"
check "container on this machine: switch on, sandbox off, image built with the start" \
  '[ "$(run --yes --dry-run --runtime host --protection container)" = 0 ] && [ "$(val main AUTOMATION_HARDENING_UNATTENDEDINCONTAINER)" = true ] && [ "$(val main AUTOMATION_HARDENING_UNATTENDEDSANDBOX)" = false ] && out | grep -F "build-agent-images.sh agent-workflow" >/dev/null'
rm -rf "$INST"
check "landlock for an instance in a container"         '[ "$(run --yes --dry-run --runtime container --protection landlock)" = 0 ] && [ "$(val main AUTOMATION_HARDENING_UNATTENDEDSANDBOX)" = true ] && [ "$(val main AUTOMATION_HARDENING_UNATTENDEDINCONTAINER)" = false ]'
rm -rf "$INST"
check "container for an instance in a container exits 2 and says why" \
  '[ "$(run --yes --dry-run --runtime container --protection container)" = 2 ] && out | grep -- "--runtime host" >/dev/null'
rm -rf "$INST"
check "off writes both switches off"                    '[ "$(run --yes --dry-run --runtime host --protection off)" = 0 ] && [ "$(val main AUTOMATION_HARDENING_UNATTENDEDINCONTAINER)" = false ] && [ "$(val main AUTOMATION_HARDENING_UNATTENDEDSANDBOX)" = false ]'
check "--help names --protection in both languages"     'bash "$SETUP" --help | grep -- "--protection" >/dev/null && bash "$SETUP" --help --lang de | grep -- "--protection" >/dev/null'
rm -rf "$INST"; mkdir -p "$INST"

echo "6b) --bind on one address: the port choice asks there, not on 127.0.0.1 (065144ee)"
# Like instance.sh (3d002903): with --bind to an address the instance only listens there. The port choice
# asked 127.0.0.1 — an occupation on the bound address it did not see (up then refused), one
# on 127.0.0.1 it dodged needlessly. A second loopback address plays the bound one; the listeners
# are fake_instance.py (they accept, otherwise the next /dev/tcp probe hangs).
BADDR=127.0.0.2
if command -v python3 >/dev/null 2>&1 && python3 -c "import socket; socket.socket().bind(('$BADDR', 0))" 2>/dev/null; then
  freeon() { local p="$2"; while (exec 3<>"/dev/tcp/$1/$p") 2>/dev/null; do p=$((p + 1)); done; echo "$p"; }   # ADDRESS BASE
  blisten() {  # ADDRESS PORT — detached, prints the PID as soon as it accepts
    local pid
    pid="$( (FAKE_INSTANCE_HOST="$1" FAKE_INSTANCE_TTL=120 python3 "$HERE/fake_instance.py" api "$2" x 0 >/dev/null 2>&1 & echo $!) )"
    for _ in $(seq 1 100); do (exec 3<>"/dev/tcp/$1/$2") 2>/dev/null && break; sleep 0.05; done
    echo "$pid"
  }
  bgone() { kill "$1" 2>/dev/null; for _ in $(seq 1 100); do kill -0 "$1" 2>/dev/null || break; sleep 0.05; done; }
  Q="$(freeon "$BADDR" 9090)"; L1=""
  # Q free on the bound address; if it is also free on 127.0.0.1, occupy it there
  (exec 3<>"/dev/tcp/127.0.0.1/$Q") 2>/dev/null || L1="$(blisten 127.0.0.1 "$Q")"
  rm -rf "$INST"
  check "taken only on 127.0.0.1: free for --bind $BADDR" '(exec 3<>"/dev/tcp/127.0.0.1/$Q") 2>/dev/null && [ "$(run --yes --dry-run --runtime host --bind $BADDR)" = 0 ] && [ "$(val main MYSURICATES_PORT)" = "$Q" ]'
  [ -z "$L1" ] || bgone "$L1"
  L2="$(blisten "$BADDR" "$Q")"; rm -rf "$INST"
  check "taken on the bound address: the next free one there" '[ "$(run --yes --dry-run --runtime host --bind $BADDR)" = 0 ] && N="$(freeon "$BADDR" $((Q + 1)))" && [ "$(val main MYSURICATES_PORT)" = "$N" ] && out | grep -F "is in use — taking $N" >/dev/null'
  bgone "$L2"; rm -rf "$INST"; mkdir -p "$INST"
else
  echo "  skip (no python3, or $BADDR is not a loopback address here — macOS)"
fi

echo "7) an existing setup is kept unless asked"
step() {  # LABEL — what follows after „✓ LABEL“; tui_done pads LABEL to 10 characters
  local pre
  pre="$1$(printf "%$(( ${#1} < 10 ? 11 - ${#1} : 1 ))s" "")"
  out | sed -n "s/^ *[^ ]* $pre\\(.*\\)\$/\\1/p" | tail -n 1
}
printf 'MYSURICATES_RUNTIME=host\nMYSURICATES_PORT=9777\nMYSURICATES_PRESET=full\n' > "$INST/main.env"
check "kept without --reconfigure"                      '[ "$(run --yes --dry-run --runtime host)" = 0 ] && [ "$(val main MYSURICATES_PORT)" = 9777 ]'
check "--preset core --force changes only the preset"   '[ "$(run --yes --dry-run --runtime host --preset core --force)" = 0 ] && [ "$(val main MYSURICATES_PRESET)" = core ] && [ "$(val main MYSURICATES_PORT)" = 9777 ]'
check "--reconfigure rewrites it"                       '[ "$(run --yes --dry-run --runtime host --reconfigure)" = 0 ] && grep -q "^# MySuricates-Instanz" "$INST/main.env"'
# --reconfigure without a terminal starts from the file's values and changes only the given flags (ca902719).
# Before, fresh defaults applied: without --port 9090, without --runtime container, preset core, first provider.
R7P="$(firstfree 9777)"
check "--reconfigure: the port of the file stays"       '[ "$(val main MYSURICATES_PORT)" = "$R7P" ] && [ "$(step Ports)" = "$R7P" ]'
printf 'MYSURICATES_RUNTIME=host\nMYSURICATES_PORT=9777\nMYSURICATES_PRESET=full\nMYSURICATES_ENVIRONMENT=Prod\nMYSURICATES_HARDENING_SEALING=true\nAUTOMATION_AI_DEFAULT_PROVIDER=PI\nAUTOMATION_HARDENING_REQUIRESIGNEDSEAL=true\nAUTOMATION_MODULES_CHAINS=false\nAUTOMATION_CUSTOM_BY_HAND=x' > "$INST/main.env"
check "--reconfigure --yes: runtime, preset, provider stay" '[ "$(run --yes --dry-run --reconfigure)" = 0 ] && [ "$(val main MYSURICATES_RUNTIME)" = host ] && [ "$(val main MYSURICATES_PRESET)" = full ] && [ "$(val main AUTOMATION_AI_DEFAULT_PROVIDER)" = PI ] && [ "$(val main MYSURICATES_PORT)" = "$R7P" ]'
check "... environment, seal switch and AUTOMATION_* lines stay" '[ "$(val main MYSURICATES_ENVIRONMENT)" = Prod ] && [ "$(val main MYSURICATES_HARDENING_SEALING)" = true ] && [ "$(val main AUTOMATION_HARDENING_REQUIRESIGNEDSEAL)" = true ] && [ "$(val main AUTOMATION_MODULES_CHAINS)" = false ] && [ "$(val main AUTOMATION_CUSTOM_BY_HAND)" = x ] && [ "$(grep -c "^AUTOMATION_AI_DEFAULT_PROVIDER=" "$INST/main.env")" = 1 ]'
check "--reconfigure --preset research: only the preset" '[ "$(run --yes --dry-run --reconfigure --preset research)" = 0 ] && [ "$(val main MYSURICATES_PRESET)" = research ] && [ "$(val main MYSURICATES_RUNTIME)" = host ] && [ "$(val main AUTOMATION_AI_DEFAULT_PROVIDER)" = PI ] && [ "$(val main MYSURICATES_PORT)" = "$R7P" ]'
R7N="$(firstfree 19790)"
check "--reconfigure --port N --provider codex: only those" '[ "$(run --yes --dry-run --reconfigure --port "$R7N" --provider codex)" = 0 ] && [ "$(val main MYSURICATES_PORT)" = "$R7N" ] && [ "$(val main AUTOMATION_AI_DEFAULT_PROVIDER)" = CODEX ] && [ "$(val main MYSURICATES_PRESET)" = research ] && [ "$(val main MYSURICATES_RUNTIME)" = host ]'
check "--reconfigure --runtime container: the runtime changes" '[ "$(run --yes --dry-run --reconfigure --runtime container)" = 0 ] && [ "$(val main MYSURICATES_RUNTIME)" = container ] && [ "$(val main MYSURICATES_PORT)" = "$R7N" ] && [ "$(val main AUTOMATION_AI_DEFAULT_PROVIDER)" = CODEX ]'
printf 'MYSURICATES_RUNTIME=container\nMYSURICATES_PORT=%s\nMYSURICATES_BIND=0.0.0.0\nMYSURICATES_PRESET=core\nMYSURICATES_PROJECTS=/srv/projekte\nMYSURICATES_DOCKER_SOCKET=1\nMYSURICATES_AI_FROM_HOST=programs\nMYSURICATES_AI_PROVIDERS=codex\nAUTOMATION_AI_DEFAULT_PROVIDER=CODEX\n' "$R7N" > "$INST/main.env"
check "--reconfigure, container: bind, projects, socket, AI access stay" '[ "$(run --yes --dry-run --reconfigure)" = 0 ] && [ "$(val main MYSURICATES_BIND)" = 0.0.0.0 ] && [ "$(val main MYSURICATES_PROJECTS)" = /srv/projekte ] && [ "$(val main MYSURICATES_DOCKER_SOCKET)" = 1 ] && [ "$(val main MYSURICATES_AI_FROM_HOST)" = programs ] && [ "$(val main MYSURICATES_AI_PROVIDERS)" = codex ]'
check "--reconfigure --mode staged: not from main.env"  '[ "$(run --yes --dry-run --reconfigure --mode staged)" = 0 ] && [ "$(val dev MYSURICATES_PRESET)" = full ] && [ "$(val prod MYSURICATES_HARDENING_SEALING)" = true ]'
rm -f "$INST/dev.env" "$INST/test.env" "$INST/prod.env"
# Same key replaces instead of appending; a preset discards old module overrides (ca902719)
printf 'MYSURICATES_RUNTIME=host\nMYSURICATES_PORT=%s\nMYSURICATES_PRESET=core\nAUTOMATION_AI_DEFAULT_PROVIDER=CODEX\nAUTOMATION_MODULES_CHAINS=true\nAUTOMATION_MODULES_GOALS=false\nAUTOMATION_MODULES_UIEDITABLE=true\nAUTOMATION_HARDENING_SEALING=true\n' "$R7N" > "$INST/main.env"
count() { grep -c "^$2=" "$INST/$1.env"; }
check "--reconfigure --modules twice: one line per key"   '[ "$(run --yes --dry-run --reconfigure --modules chains,memory)" = 0 ] && [ "$(run --yes --dry-run --reconfigure --modules memory)" = 0 ] && [ "$(count main AUTOMATION_MODULES_CHAINS)" = 1 ] && [ "$(count main AUTOMATION_MODULES_MEMORY)" = 1 ] && [ "$(count main AUTOMATION_HARDENING_SEALING)" = 1 ] && [ "$(val main AUTOMATION_MODULES_GOALS)" = false ]'
check "--reconfigure --preset: old module overrides go, hardening and ui-editable stay" '[ "$(run --yes --dry-run --reconfigure --preset research)" = 0 ] && [ "$(val main MYSURICATES_PRESET)" = research ] && [ "$(count main AUTOMATION_MODULES_CHAINS)" = 0 ] && [ "$(count main AUTOMATION_MODULES_GOALS)" = 0 ] && [ "$(count main AUTOMATION_MODULES_MEMORY)" = 0 ] && [ "$(val main AUTOMATION_HARDENING_SEALING)" = true ] && [ "$(val main AUTOMATION_MODULES_UIEDITABLE)" = true ]'
check "--reconfigure --preset --modules: only the new override" '[ "$(run --yes --dry-run --reconfigure --preset core --modules bpmn)" = 0 ] && [ "$(val main AUTOMATION_MODULES_BPMN)" = true ] && [ "$(grep -c "^AUTOMATION_MODULES_" "$INST/main.env")" = 2 ]'

echo "7b) a kept file: the steps show its values, not ones that never reach it (fd4eb89f)"
# Before, the ports step reported „nehme 9091“ (the instance's own running one occupies 9090) and the
# AI step a new default provider — neither ever ended up in the kept file.
rm -rf "$INST"; mkdir -p "$INST"
printf 'MYSURICATES_RUNTIME=host\nMYSURICATES_PORT=9777\nMYSURICATES_PRESET=full\nAUTOMATION_AI_DEFAULT_PROVIDER=PI\n' > "$INST/main.env"
cp "$INST/main.env" "$TMP/kept.env"
check "kept: exit 0, the file stays as it was"          '[ "$(run --yes --dry-run)" = 0 ] && cmp -s "$INST/main.env" "$TMP/kept.env"'
check "ports: the port of the file, marked"             '[ "$(step Ports)" = "9777 · from deploy/instances/main.env" ]'
check "AI: the provider of the file, marked"            '[ "$(step AI)" = "pi · from deploy/instances/main.env" ]'
check "mode and modules: from the file, marked"         '[ "$(step Mode)" = "One instance — on this machine · from deploy/instances/main.env" ] && [ "$(step Modules)" = "full · from deploy/instances/main.env" ]'
check "the login hint names the provider of the file"   'out | grep -F "Log in to pi before the first AI workflow" >/dev/null'
# After the command the hint in the installer's language: the catalog names only the key (eab43a8b)
check "... pi's note after the command, English"        'out | grep -F "pi, then /login in pi — or an API key as environment variable (see pi --help)" >/dev/null'
check "... and German with --lang de"                   '[ "$(run --yes --dry-run --lang de)" = 0 ] && out | grep -F "Vor dem ersten KI-Workflow bei pi anmelden:" >/dev/null && out | grep -F "pi, dann /login in pi — oder ein API-Schlüssel als Umgebungsvariable (siehe pi --help)" >/dev/null && ! out | grep "then /login" >/dev/null'
check "flags that do not apply are named"              '[ "$(run --yes --dry-run --port 9555 --provider codex --runtime container)" = 0 ] && out | grep -F "not used: --runtime container, --port 9555, --provider codex" >/dev/null && cmp -s "$INST/main.env" "$TMP/kept.env" && [ "$(step Ports)" = "9777 · from deploy/instances/main.env" ]'
check "--preset --force: the preset goes into the file" '[ "$(run --yes --dry-run --preset research --force)" = 0 ] && [ "$(val main MYSURICATES_PRESET)" = research ] && [ "$(step Modules)" = "research · written into deploy/instances/main.env" ] && ! out | grep "not used" >/dev/null'
cp "$TMP/kept.env" "$INST/main.env"
check "German: aus der Datei"                           '[ "$(run --yes --dry-run --lang de)" = 0 ] && [ "$(step Ports)" = "9777 · aus deploy/instances/main.env" ]'
printf 'MYSURICATES_PORT=19730\nAUTOMATION_AI_DEFAULT_PROVIDER=CODEX\nMYSURICATES_AI_FROM_HOST=programs\nMYSURICATES_PROJECTS=/srv/projekte\n' > "$INST/main.env"
check "container file: access and projects from it"     '[ "$(run --yes --dry-run)" = 0 ] && [ "$(step "AI in the container")" = "programs · from deploy/instances/main.env" ] && [ "$(step Ports)" = "19730 · /srv/projekte · from deploy/instances/main.env" ]'
if command -v python3 >/dev/null 2>&1; then
  # The case from the TODO: the instance's own running one occupies the file's port
  LP="$(firstfree 19740)"
  ( python3 -c "import socket,time; s=socket.socket(); s.bind(('127.0.0.1', $LP)); s.listen(1); time.sleep(60)" >/dev/null 2>&1 & echo $! > "$TMP/listener.pid" )
  for _ in $(seq 1 100); do (exec 3<>"/dev/tcp/127.0.0.1/$LP") 2>/dev/null && break; sleep 0.05; done
  printf 'MYSURICATES_RUNTIME=host\nMYSURICATES_PORT=%s\n' "$LP" > "$INST/main.env"
  check "own instance on the port: no 'is in use — taking'" '[ "$(run --yes --dry-run)" = 0 ] && ! out | grep "is in use" >/dev/null && [ "$(step Ports)" = "$LP · from deploy/instances/main.env" ]'
  kill "$(cat "$TMP/listener.pid")" 2>/dev/null
else
  echo "  skip (no python3 for a listener)"
fi
rm -rf "$INST"; mkdir -p "$INST"
for f in dev:19710 test:19711 prod:19712; do printf 'MYSURICATES_RUNTIME=container\nMYSURICATES_PORT=%s\nAUTOMATION_AI_DEFAULT_PROVIDER=PI\n' "${f#*:}" > "$INST/${f%%:*}.env"; done
check "staged, all kept: ports from the files, marked"  '[ "$(run --yes --dry-run --mode staged)" = 0 ] && [ "$(step Ports)" = "Dev 19710 · Test 19711 · Prod 19712 · files kept: dev, test, prod" ]'
check "staged, all kept: AI from the files"             '[ "$(step AI)" = "pi · files kept: dev, test, prod" ]'
printf 'MYSURICATES_RUNTIME=container\nMYSURICATES_PORT=19711\nAUTOMATION_AI_DEFAULT_PROVIDER=CODEX\n' > "$INST/test.env"
check "staged, differing providers: each named; flags not used are named" '[ "$(run --yes --dry-run --mode staged --provider claude)" = 0 ] && [ "$(step AI)" = "dev pi · test codex · prod pi · files kept: dev, test, prod" ] && out | grep -F "Keeping deploy/instances/{dev,test,prod}.env — not used: --provider claude" >/dev/null'
rm -f "$INST/test.env"
T2="$(firstfree 8090 "19710 19712")"
check "staged, one new: its port avoids the kept ones"  '[ "$(run --yes --dry-run --mode staged)" = 0 ] && [ "$(val test MYSURICATES_PORT)" = "$T2" ] && [ "$(step Ports)" = "Dev 19710 · Test $T2 · Prod 19712 · files kept: dev, prod" ]'
check "staged, one new: the provider goes into the new file" '[ "$(val test AUTOMATION_AI_DEFAULT_PROVIDER)" = CODEX ] && [ "$(step AI)" = "codex · files kept: dev, prod" ]'
rm -rf "$INST"

echo "8) language"
rm -rf "$INST"
run --yes --dry-run --runtime host >/dev/null
check "English by default"                              'out | grep -E "Mode|Checks" >/dev/null'
rm -rf "$INST"
run --yes --dry-run --runtime host --lang de >/dev/null
check "German with --lang de"                           'out | grep -E "Modus|Prüfung" >/dev/null'
# The three commands in the closing box start in the same column — in every language
# Column via bash (counts characters); busybox-awk index() counts bytes and slips at „Ä“
cols() { out | grep -E "instance.sh key --name|up\|down\|status --name|setup.sh · deploy" | while IFS= read -r l; do l="${l%%scripts/*}"; echo "${#l}"; done | sort -u | wc -l | tr -d " "; }
check "done box: commands aligned (de)"                  '[ "$(cols)" = 1 ]'
rm -rf "$INST"; run --yes --dry-run --runtime host >/dev/null
check "done box: commands aligned (en)"                  '[ "$(cols)" = 1 ]'

echo "8b) menus fit the panel — nothing cut off (pseudo terminal, stub tools)"
# Truncated = „…“ right before the right frame; „Eigene Auswahl…“ in the middle of the line is intended
cut_off() { sed "s/$(printf "\033")\[[0-9;?]*[A-Za-z]//g" "$1" | tr -d "\r" | grep -E "…[[:space:]]*│[[:space:]]*$" >/dev/null; }
pty_setup() {  # LANGUAGE KEYS FLAGS… → recording in $TMP/menu
  local l="$1" keys="$2"; shift 2
  rm -rf "$INST"
  printf "$keys" | script -qec "stty cols 120 rows 50; env HOME='$TMP/home' PATH='$TMP/bin:$TMP/tools' XDG_DATA_HOME='$TMP/data' XDG_STATE_HOME='$TMP/state' XDG_CONFIG_HOME='$TMP/home/.config' MYSURICATES_INSTANCES_DIR='$INST' TERM=xterm-256color bash '$SETUP' --dry-run --lang $l $*" /dev/null > "$TMP/menu" 2>&1
}
if command -v script >/dev/null 2>&1 && script -qec true /dev/null >/dev/null 2>&1; then
  for l in en de; do
    pty_setup "$l" q
    check "mode menu shown and complete ($l)"             'grep -E "(Step|Schritt) 2 (of|von)" "$TMP/menu" >/dev/null && ! cut_off "$TMP/menu"'
    pty_setup "$l" q --runtime host
    check "module menu shown and complete ($l)"           'grep -E "(Step|Schritt) 4 (of|von)" "$TMP/menu" >/dev/null && ! cut_off "$TMP/menu"'
    pty_setup "$l" 'n\nq' --runtime container --preset core --provider codex   # n: do not install pi as well
    sed "s/$(printf "\033")\[[0-9;?]*[A-Za-z]//g" "$TMP/menu" | tr -d "\r" | grep -A4 -E "(in den Container|into the container)\?" > "$TMP/ai-menu"
    check "AI access menu shown and complete ($l)"       '[ -s "$TMP/ai-menu" ] && ! cut_off "$TMP/ai-menu"'
  done
else
  echo "  skip (no usable script command)"
fi

echo "8c) change settings in a terminal: the file's values are asked as defaults (ca902719)"
# Before, „Einstellungen aendern“ (and --reconfigure in a terminal) skipped every step whose
# value the file already named — mode, modules, provider, port could not be changed at all.
# The keys come in groups, each only once the output is there: menus read characters without
# line buffering, follow-up questions whole lines — sent all at once, a line question swallowed
# the supply of the next menus.
feed() {  # FILE GROUP… — each group, as soon as the output has not grown for 0.2 s
  local f="$1" k last cur n; shift
  for k in "$@"; do
    last=-1; n=0
    while [ "$n" -lt 150 ]; do sleep 0.2; cur="$(wc -c < "$f" 2>/dev/null || echo 0)"; [ "$cur" -gt 0 ] && [ "$cur" = "$last" ] && break; last="$cur"; n=$((n + 1)); done
    printf "$k"
  done
  sleep 1
}
pty_run() {  # "GROUP|GROUP|…" FLAGS… → recording in $TMP/menu(.txt), without touching $INST
  local groups; IFS='|' read -r -a groups <<< "$1"; shift
  : > "$TMP/menu"
  feed "$TMP/menu" "${groups[@]}" | timeout 120 script -qec "stty cols 120 rows 50; env HOME='$TMP/home' PATH='$TMP/bin:$TMP/tools' XDG_DATA_HOME='$TMP/data' XDG_STATE_HOME='$TMP/state' XDG_CONFIG_HOME='$TMP/home/.config' MYSURICATES_INSTANCES_DIR='$INST' TERM=xterm-256color bash '$SETUP' --dry-run --lang en $*" /dev/null > "$TMP/menu" 2>&1
  sed "s/$(printf "\033")\[[0-9;?]*[A-Za-z]//g" "$TMP/menu" | tr -d "\r" > "$TMP/menu.txt"
}
if command -v script >/dev/null 2>&1 && script -qec true /dev/null >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1; then
  C8="$(firstfree 19820)"; C8N="$(firstfree $((C8 + 1)))"
  c8_file() { rm -rf "$INST"; mkdir -p "$INST"; printf 'MYSURICATES_RUNTIME=host\nMYSURICATES_PORT=%s\nMYSURICATES_PRESET=full\nAUTOMATION_AI_DEFAULT_PROVIDER=PI\n' "$C8" > "$INST/main.env"; }
  asked() { grep -F "$1" "$TMP/menu.txt" >/dev/null; }
  # mode Enter · modules Enter · protection Enter · install pi n · provider Enter · port Enter · build n
  c8_file; pty_run '\n|\n|\n|n\n|\n|\n|n\n' --reconfigure
  check "--reconfigure: mode, modules, provider and port are asked" 'asked "Step 2 of 8" && asked "Step 4 of 8" && asked "Which provider should workflows use by default?" && asked "[$C8]:"'
  check "... with the modules the protection of unattended runs, suggesting the container here" \
    'asked "Protection of unattended runs" && [ "$(val main AUTOMATION_HARDENING_UNATTENDEDINCONTAINER)" = true ] && [ "$(val main AUTOMATION_HARDENING_UNATTENDEDSANDBOX)" = false ]'
  check "... Enter everywhere keeps the file's values"   '[ "$(val main MYSURICATES_RUNTIME)" = host ] && [ "$(val main MYSURICATES_PRESET)" = full ] && [ "$(val main AUTOMATION_AI_DEFAULT_PROVIDER)" = PI ] && [ "$(val main MYSURICATES_PORT)" = "$C8" ]'
  # Modules 2 (research), new port — the rest stays
  c8_file; pty_run "\\n|2|\\n|n\\n|\\n|$C8N\\n|n\\n" --reconfigure
  check "... a choice changes only that value"           '[ "$(val main MYSURICATES_PRESET)" = research ] && [ "$(val main MYSURICATES_PORT)" = "$C8N" ] && [ "$(val main MYSURICATES_RUNTIME)" = host ] && [ "$(val main AUTOMATION_AI_DEFAULT_PROVIDER)" = PI ]'
  # Without --reconfigure: „Einstellungen aendern“ in the selection menu (Enter), then the same questions
  c8_file; pty_run '\n|\n|\n|\n|n\n|\n|\n|n\n'
  check "change settings: asked with the file's values"  'asked "Change settings of main" && asked "Step 4 of 8" && asked "[$C8]:" && [ "$(val main MYSURICATES_PRESET)" = full ] && [ "$(val main MYSURICATES_RUNTIME)" = host ] && [ "$(val main AUTOMATION_AI_DEFAULT_PROVIDER)" = PI ]'
  # „Eigene“ in the file, a preset chosen in the menu: the old module overrides no longer apply
  c8_file; printf 'AUTOMATION_MODULES_CHAINS=true\nAUTOMATION_MODULES_GOALS=false\nAUTOMATION_HARDENING_SEALING=true\n' >> "$INST/main.env"
  pty_run '\n|1|\n|n\n|\n|\n|n\n' --reconfigure
  check "custom → preset in the menu: old module overrides gone, hardening stays" '[ "$(val main MYSURICATES_PRESET)" = core ] && ! grep -q "^AUTOMATION_MODULES_" "$INST/main.env" && [ "$(val main AUTOMATION_HARDENING_SEALING)" = true ]'
  # Given by flag: not asked again (pi n · build n)
  c8_file; pty_run 'n\n|n\n' --reconfigure --runtime host --preset core --provider codex --port "$C8N"
  check "flags are not asked again"                      '! asked "Step 2 of 8" && ! asked "Step 4 of 8" && ! asked "[$C8N]:" && [ "$(val main MYSURICATES_PRESET)" = core ] && [ "$(val main AUTOMATION_AI_DEFAULT_PROVIDER)" = CODEX ]'
  # „Neu einrichten“ does not start from the file: selection 2 · mode 2 (machine) · modules · protection · pi n · port · build n
  c8_file; pty_run '2|2|\n|\n|n\n|\n|n\n'
  check "set up anew: the file's values do not count"    'asked "Set up again" && [ "$(val main MYSURICATES_PRESET)" = core ] && [ "$(val main AUTOMATION_AI_DEFAULT_PROVIDER)" = CODEX ] && [ "$(val main MYSURICATES_PORT)" = "$(firstfree 9090)" ]'
  rm -rf "$INST"
else
  echo "  skip (no usable script or timeout command)"
fi

echo "9) the API key hint never points to the log (bc6d1902)"
check "no 'backend log' in the installer"               '! grep -niE "backend.?log|AUTO-GENERATED" "$SETUP" >/dev/null'
FE="$HERE/../../ai-automation-frontend/src/i18n"
check "login dialog does not send users to the log"     '! grep -hE "^ *authHint:" "$FE/de.ts" "$FE/en.ts" | grep -iE "log|AUTO-GENERATED" >/dev/null'
check "dev starter does not send users to the log"      '! grep -n "siehe Backend-Log" "$HERE/../start-mysuricates-dev.sh" >/dev/null'

echo "9b) the build step says whether it built — also after git pull (1c016162)"
# Without --dry-run, but in a throwaway repo with stubs: no Maven, no npm, java only sleeps.
# Before, the step after git pull reported „✓ bauen 0:00“, and the instance ran with the old build.
SREPO="$TMP/srepo"; SINST="$TMP/sinst"; SSTATE="$TMP/state2"; B2="$TMP/bin2"
mkdir -p "$SREPO/scripts" "$SREPO/ai-automation-backend" "$SREPO/ai-automation-frontend" "$B2"
cp "$SETUP" "$HERE/../instance.sh" "$SREPO/scripts/"; ln -s "$HERE/../lib" "$SREPO/scripts/lib"
cp "$HERE/../../ai-automation-backend/pom.xml" "$SREPO/ai-automation-backend/"
printf '#!/bin/sh\necho build >> "$MVN_LOG"\nrm -rf target && mkdir -p target && git rev-parse --short HEAD > target/ai-automation-backend-9.9.9.jar\n' > "$SREPO/ai-automation-backend/mvnw"
chmod +x "$SREPO/ai-automation-backend/mvnw"
printf 'target/\ndist/\n' > "$SREPO/.gitignore"
for t in git nohup; do ln -sf "$(command -v "$t")" "$B2/$t"; done
printf '#!/bin/sh\ncase "$1" in -version|--version) echo "openjdk version \\"21.0.8\\" 2025-07-15" >&2 ;; *) exec sleep 300 ;; esac\n' > "$B2/java"
printf '#!/bin/sh\ncase "$*" in --version*) echo "curl 8.5.0 (x86_64)" ;; *system/health*) echo "{\\"status\\":\\"UP\\"}" ;; *) exit 7 ;; esac\n' > "$B2/curl"
printf '#!/bin/sh\necho v24.21.0\n' > "$B2/node"
printf '#!/bin/sh\ncase "$1" in -v|--version) echo 11.19.0 ;; run) mkdir -p dist && echo html > dist/index.html ;; esac\nexit 0\n' > "$B2/npm"
chmod +x "$B2/java" "$B2/curl" "$B2/node" "$B2/npm"
scommit() { git -C "$SREPO" add -A && git -C "$SREPO" -c user.name=t -c user.email=t@t commit -qm "$1"; }
git -C "$SREPO" init -q && scommit eins
SPORT="$(firstfree 19480)"
export MVN_LOG="$TMP/mvn.log"; : > "$MVN_LOG"
senv() { env HOME="$TMP/home" PATH="$B2:$TMP/tools" XDG_DATA_HOME="$TMP/data" XDG_STATE_HOME="$SSTATE" \
  XDG_CONFIG_HOME="$TMP/home/.config" MYSURICATES_INSTANCES_DIR="$SINST" TERM=dumb INSTANCE_HEALTH_TIMEOUT=10 "$@"; }
srun() { senv bash "$SREPO/scripts/setup.sh" --yes --runtime host --port "$SPORT" "$@" < /dev/null > "$TMP/out" 2>&1; echo $?; }
sinst() { senv bash "$SREPO/scripts/instance.sh" "$@" --name main; }
builds() { grep -c build "$MVN_LOG"; }
spid() { cat "$SSTATE/mysuricates/main/instance.pid" 2>/dev/null; }
check "fresh: exit 0, built once, says build"           '[ "$(srun)" = 0 ] && [ "$(builds)" = 1 ] && out | grep "Build and start main" >/dev/null'
check "done box: how to update after git pull"          'out | grep "git pull && scripts/instance.sh down --name main" >/dev/null && out | grep -E "^[│|] +scripts/instance.sh up --name main" >/dev/null'
H1="$(git -C "$SREPO" rev-parse HEAD)"; P1="$(spid)"
check "running, same state: not rebuilt, says so"       '[ "$(srun)" = 0 ] && [ "$(builds)" = 1 ] && out | grep "main is already running — existing build, not rebuilt (state ${H1:0:8})" >/dev/null && ! out | grep "Build and start" >/dev/null && [ "$(spid)" = "$P1" ]'
echo neu > "$SREPO/neu.txt"; scommit zwei; H2="$(git -C "$SREPO" rev-parse HEAD)"
check "git pull while running: rebuilt and restarted"   '[ "$(srun)" = 0 ] && [ "$(builds)" = 2 ] && out | grep "main runs state ${H1:0:8}, the clone is at ${H2:0:8}" >/dev/null && [ "$(spid)" != "$P1" ]'
check "the instance now runs the new build"             '[ "$(cat "$SSTATE/mysuricates/main/app.jar")" = "$(git -C "$SREPO" rev-parse --short HEAD)" ] && [ "$(sinst built)" = "current $H2" ]'
sinst down >/dev/null 2>&1
check "stopped, same state: started without a build"    '[ "$(srun --lang de)" = 0 ] && [ "$(builds)" = 2 ] && out | grep "main starten — vorhanden, nicht neu gebaut (Stand ${H2:0:8})" >/dev/null'
P2="$(spid)"
check "--reconfigure: restarted, not rebuilt"          '[ "$(srun --reconfigure)" = 0 ] && [ "$(builds)" = 2 ] && [ "$(spid)" != "$P2" ] && out | grep "Start main — existing build, not rebuilt" >/dev/null'
sinst down >/dev/null 2>&1

echo "9c) a restart never aborts running workflow runs unasked (29820f58)"
# A restart aborts running runs, after which they show up as ghost runs in the history. The installer
# asks the instance first, read-only — key from `instance.sh key`, port from the instance file. The
# fake API is the curl stand-in: health always answers, the runs query only on port FAKE_PORT
# with the key FAKE_KEY on stdin; without FAKE_RUNS it does not answer (curl exit 7).
cat > "$B2/curl" <<'STUB'
#!/bin/sh
case "$*" in
  --version*) echo "curl 8.5.0 (x86_64)" ;;
  *system/health*) echo '{"status":"UP"}' ;;
  *executions*status=RUNNING*)
    echo "$*" >> "$RUNS_LOG"
    hdr=""; case "$*" in *"-H @-"*) hdr="$(cat)" ;; esac
    [ -n "${FAKE_RUNS:-}" ] || exit 7
    case "$*" in *"http://127.0.0.1:$FAKE_PORT/"*) ;; *) exit 7 ;; esac
    [ "$hdr" = "X-API-Key: $FAKE_KEY" ] || exit 22
    printf '{"content":[{"id":"r1","workflowName":"x"}],"page":0,"size":1,"totalElements":%s,"totalPages":%s}\n' "$FAKE_RUNS" "$FAKE_RUNS" ;;
  *) exit 7 ;;
esac
STUB
chmod +x "$B2/curl"
export RUNS_LOG="$TMP/runs.log" FAKE_PORT="$SPORT" FAKE_KEY="key-29820f58-test"; : > "$RUNS_LOG"
SKEY="$SSTATE/mysuricates/main/home/.mysuricates/api-key"   # the real backend writes it at startup
sval() { sed -n "s/^$1=//p" "$SINST/main.env" 2>/dev/null; }   # not cut: 8b overrides it as a function
srun >/dev/null; mkdir -p "$(dirname "$SKEY")"; printf '%s\n' "$FAKE_KEY" > "$SKEY"
echo drei > "$SREPO/drei.txt"; scommit drei; H3="$(git -C "$SREPO" rev-parse HEAD)"; P3="$(spid)"; B3="$(builds)"
check "runs active, --yes: neither stopped nor rebuilt"  '[ "$(FAKE_RUNS=2 srun)" = 0 ] && [ "$(spid)" = "$P3" ] && kill -0 "$P3" 2>/dev/null && [ "$(builds)" = "$B3" ]'
check "... says how many and how to restart later"      'out | grep -F "main: 2 workflow run(s) still running" >/dev/null && out | grep -F "scripts/instance.sh down --name main && scripts/instance.sh up --name main" >/dev/null && ! out | grep "building anew and restarting" >/dev/null'
check "... names the old and the new state"             'out | grep -F "main keeps running state ${H2:0:8}, not restarted (the clone is at ${H3:0:8})" >/dev/null'
check "... done box still shows the running instance"   'out | grep -F "http://localhost:$SPORT" >/dev/null'
check "asked with the key on stdin, never in arguments"  '[ -s "$RUNS_LOG" ] && ! grep -qF "$FAKE_KEY" "$RUNS_LOG" && grep -qF "http://127.0.0.1:$SPORT/api/v1/executions?status=RUNNING" "$RUNS_LOG"'
check "German: the same, in German"                     '[ "$(FAKE_RUNS=1 srun --lang de)" = 0 ] && [ "$(spid)" = "$P3" ] && out | grep -F "main: noch 1 Workflow-Run(s) aktiv" >/dev/null'
check "no runs: rebuilt and restarted as before"        '[ "$(FAKE_RUNS=0 srun)" = 0 ] && [ "$(spid)" != "$P3" ] && [ "$(builds)" = "$((B3 + 1))" ] && ! out | grep "still running" >/dev/null'
echo vier > "$SREPO/vier.txt"; scommit vier; P4="$(spid)"; B4="$(builds)"
check "instance not answering: as before, and says so"  '[ "$(srun)" = 0 ] && [ "$(spid)" != "$P4" ] && [ "$(builds)" = "$((B4 + 1))" ] && out | grep -F "main: running workflow runs could not be checked" >/dev/null'
P5="$(spid)"; SPORT2="$(firstfree $((SPORT + 1)))"
check "--reconfigure, runs active: not restarted"       '[ "$(FAKE_RUNS=1 srun --reconfigure --port "$SPORT2")" = 0 ] && [ "$(spid)" = "$P5" ] && [ "$(sval MYSURICATES_PORT)" = "$SPORT2" ]'
check "... asked the port it runs on, shows that one"   'out | grep -F "main: 1 workflow run(s) still running" >/dev/null && out | grep -F "main keeps running with its previous settings" >/dev/null && out | grep -F "http://localhost:$SPORT" >/dev/null && ! out | grep -F "http://localhost:$SPORT2" >/dev/null'
sinst down >/dev/null 2>&1; srun --reconfigure >/dev/null   # back to SPORT, running
printf '%s\n' "$FAKE_KEY" > "$SKEY"; echo fuenf > "$SREPO/fuenf.txt"; scommit fuenf; P6="$(spid)"
spty() {  # KEYS → recording in $TMP/menu; in a terminal, without --yes
  printf "$1" | timeout 120 script -qec "stty cols 120 rows 50; env HOME='$TMP/home' PATH='$B2:$TMP/tools' XDG_DATA_HOME='$TMP/data' XDG_STATE_HOME='$SSTATE' XDG_CONFIG_HOME='$TMP/home/.config' MYSURICATES_INSTANCES_DIR='$SINST' TERM=xterm-256color INSTANCE_HEALTH_TIMEOUT=10 RUNS_LOG='$RUNS_LOG' FAKE_PORT='$FAKE_PORT' FAKE_KEY='$FAKE_KEY' FAKE_RUNS=3 MVN_LOG='$MVN_LOG' bash '$SREPO/scripts/setup.sh' --lang en --runtime host --preset core --provider codex --port $SPORT" /dev/null > "$TMP/menu" 2>&1
}
if command -v script >/dev/null 2>&1 && script -qec true /dev/null >/dev/null 2>&1 && command -v timeout >/dev/null 2>&1; then
  spty 'n\n\n\n'   # pi: no · build and start: yes · restart anyway: default (no)
  check "guided: asks, default is no — keeps running"   'grep -qF "main: 3 workflow run(s) still running — a restart aborts them. Restart anyway?" "$TMP/menu" && grep -qF "[y/N]" "$TMP/menu" && [ "$(spid)" = "$P6" ] && kill -0 "$P6" 2>/dev/null'
  spty 'n\n\ny\n'  # … restart anyway: yes
  check "guided: yes restarts on the new state"         '[ "$(spid)" != "$P6" ] && [ "$(sinst built)" = "current $(git -C "$SREPO" rev-parse HEAD)" ]'
else
  echo "  skip (no usable script or timeout command)"
fi
sinst down >/dev/null 2>&1
# In a container `up` replaces a running container as soon as image or settings have changed
# — that cannot be seen beforehand. If the container runs and runs are running: ask first.
cat > "$B2/docker" <<'STUB'
#!/bin/sh
echo "$*" >> "$DOCKER_LOG"
case "$1" in --version) echo "Docker version 29.1.3, build x"; exit 0 ;; info) exit 0 ;; build) cat > /dev/null; exit 0 ;; esac
case "$*" in
  *com.docker.compose.project*) [ -n "${CONTAINER_UP:-}" ] || exit 1; echo mysuricates-box ;;
  *"{{.State.Status}} {{.State.Health.Status}}"*) [ -n "${CONTAINER_UP:-}" ] || exit 1; echo "running healthy" ;;
  *"{{.State.Health.Status}}"*) echo healthy ;;
  exec*api-key*) [ -n "${CONTAINER_UP:-}" ] || exit 1; echo "$FAKE_KEY" ;;
esac
exit 0
STUB
chmod +x "$B2/docker"; export DOCKER_LOG="$TMP/docker2.log"
crun() { : > "$DOCKER_LOG"; : > "$RUNS_LOG"; srun --runtime container --name box "$@"; }
check "container not running: up as before"             '[ "$(crun)" = 0 ] && grep -q "up -d --no-build" "$DOCKER_LOG"'
check "container running, runs active: not touched"     '[ "$(CONTAINER_UP=1 FAKE_RUNS=2 crun)" = 0 ] && ! grep -q "up -d" "$DOCKER_LOG" && ! grep -q "^build " "$DOCKER_LOG" && out | grep -F "box: 2 workflow run(s) still running" >/dev/null && out | grep -F "scripts/instance.sh down --name box && scripts/instance.sh up --name box" >/dev/null'
check "... asked with the key from the container"       '[ -s "$RUNS_LOG" ] && grep -q "^exec .*mysuricates-box sh -c .*api-key" "$DOCKER_LOG"'
check "container running, no runs: up as before"        '[ "$(CONTAINER_UP=1 FAKE_RUNS=0 crun)" = 0 ] && grep -q "up -d --no-build" "$DOCKER_LOG"'
rm -f "$SINST/box.env" "$SINST/box.override.yml"

echo "9d) --reconfigure without a terminal and without --port keeps the port of the own running instance (0e5b82b7)"
# "Occupied" meant: something accepts connections — the instance's own running one counted too. --reconfigure
# without --port therefore wrote the next free port into the file (9090 → 9091) and restarted it
# there. Here the java stub really listens (fake_instance.py), on the port setup.sh would take first
# without --port; the fake API only plays the listener, health still comes from the curl stub.
listening() { for _ in $(seq 1 100); do (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && return 0; sleep 0.05; done; return 1; }
if command -v python3 >/dev/null 2>&1; then
  PY="$(command -v python3)"
  # Listeners that accept (fake_instance.py) — started detached, the trap removes their PIDs
  listener() { ( "$PY" "$HERE/fake_instance.py" api "$1" x 0 >/dev/null 2>&1 & echo $! > "$TMP/$2.pid" ); listening "$1"; }
  trap 'kill $(cat "$TMP/foreign.pid" "$TMP/lp.pid" "$SSTATE/mysuricates/main/instance.pid" 2>/dev/null) 2>/dev/null; rm -rf "$TMP"' EXIT
  printf '#!/bin/sh\ncase "$1" in -version|--version) echo "openjdk version \\"21.0.8\\" 2025-07-15" >&2 ;; *) exec "%s" "%s" api "$AUTOMATION_PORT" k 0 ;; esac\n' \
    "$PY" "$HERE/fake_instance.py" > "$B2/java"
  OWN="$(firstfree 9090)"
  srun --reconfigure --port "$OWN" >/dev/null; P7="$(spid)"
  check "the own instance runs and listens on its port"  '[ -n "$P7" ] && kill -0 "$P7" 2>/dev/null && listening "$OWN"'
  sreconf() { senv bash "$SREPO/scripts/setup.sh" --yes --runtime host --reconfigure "$@" < /dev/null > "$TMP/out" 2>&1; echo $?; }
  check "--reconfigure without --port: its port stays"   '[ "$(sreconf)" = 0 ] && [ "$(sval MYSURICATES_PORT)" = "$OWN" ] && [ "$(step Ports)" = "$OWN" ]'
  check "... restarted there, shown there"               '[ "$(spid)" != "$P7" ] && kill -0 "$(spid)" 2>/dev/null && listening "$OWN" && out | grep -F "http://localhost:$OWN" >/dev/null'
  sinst down >/dev/null 2>&1
  # Control sample: if the instance is not running, a listener on the port still counts as occupied
  listener "$OWN" foreign; NEXT="$(firstfree 9090)"
  check "stopped, someone else on the port: the next free one" '[ "$NEXT" != "$OWN" ] && [ "$(sreconf)" = 0 ] && [ "$(sval MYSURICATES_PORT)" = "$NEXT" ]'
  sinst down >/dev/null 2>&1; kill "$(cat "$TMP/foreign.pid")" 2>/dev/null; rm -f "$TMP/foreign.pid"
  # The helpers on their own — also for Dev · Test · Prod (kept_port): instance.sh is a stub that reports main
  # as running as long as the marker exists
  LP="$(firstfree 19750)"; listener "$LP" lp
  mkdir -p "$TMP/ports/scripts" "$TMP/ports/inst"
  printf '#!/bin/sh\nif [ "$3" = main ] && [ -e "%s" ]; then echo "running healthy"; else echo "nicht gestartet"; fi\n' "$TMP/ports/up" > "$TMP/ports/scripts/instance.sh"
  printf 'MYSURICATES_PORT=%s\nMYSURICATES_PORT=%s\n' "$((LP + 7))" "$LP" > "$TMP/ports/inst/main.env"   # twice: the last line wins
  # unset -f cut: 8b has overridden cut in this shell as a function, the helpers need the real one
  lib() { ( unset -f cut; SCRIPT_DIR="$TMP/ports/scripts" INSTANCES_DIR="$TMP/ports/inst" RECONF=1; . "$HERE/../lib/instance-runs.sh"; . "$HERE/../lib/setup-kept.sh"; . "$HERE/../lib/setup-ports.sh"; eval "$1" ); }
  touch "$TMP/ports/up"
  check "lib: the running own instance's port counts as free" '[ "$(lib "free_port $LP \"\" main")" = "$LP" ] && [ "$(lib "kept_port main $LP \"\"")" = "$LP" ]'
  check "lib: not for another name, not when taken in this run" '[ "$(lib "free_port $LP \"\" dev")" != "$LP" ] && [ "$(lib "free_port $LP \"$LP\" main")" != "$LP" ] && [ "$(lib "free_port $LP")" != "$LP" ]'
  rm -f "$TMP/ports/up"
  check "lib: own instance stopped → in use as before"   '[ "$(lib "free_port $LP \"\" main")" != "$LP" ]'
  kill "$(cat "$TMP/lp.pid")" 2>/dev/null; rm -f "$TMP/lp.pid"
  printf '#!/bin/sh\ncase "$1" in -version|--version) echo "openjdk version \\"21.0.8\\" 2025-07-15" >&2 ;; *) exec sleep 300 ;; esac\n' > "$B2/java"
else
  echo "  skip (no python3)"
fi
printf 'MYSURICATES_RUNTIME=host\nMYSURICATES_PORT=19761\nMYSURICATES_PORT=19762\nMYSURICATES_PRESET=full\nAUTOMATION_AI_DEFAULT_PROVIDER=PI\nAUTOMATION_AI_DEFAULT_PROVIDER=CODEX\n' > "$TMP/dup.env"
LE="$( unset -f cut; INSTANCES_DIR="$TMP" RECONF=0; RUNTIME="" PORT="" PRESET="" PROVIDER="" PROJECTS=""; . "$HERE/../lib/setup-kept.sh"; load_existing dup; printf '%s|%s|%s|%s|%s' "$RUNTIME" "$PORT" "$PRESET" "$PROVIDER" "$NAME" )"
check "load_existing: one value per key, the last line wins" '[ "$LE" = "host|19762|full|codex|dup" ]'

echo "9e) load_existing also keeps bind, ai-from-host, ai-providers, docker-socket and hardening (fbb1c61e)"
printf 'MYSURICATES_RUNTIME=container\nMYSURICATES_PORT=19770\nMYSURICATES_PRESET=full\nAUTOMATION_AI_DEFAULT_PROVIDER=CODEX\nMYSURICATES_BIND=127.0.0.2\nMYSURICATES_AI_FROM_HOST=all\nMYSURICATES_AI_PROVIDERS=codex,pi\nMYSURICATES_DOCKER_SOCKET=1\nAUTOMATION_HARDENING_SEALING=true\n' > "$TMP/harden.env"
LEH() {  # BIND AI_FROM_HOST AI_PROVIDERS SOCKET_FLAG SOCKET → BIND|AI_FROM_HOST|AI_PROVIDERS|SOCKET|CUSTOM_LINES after load_existing
  ( unset -f cut; INSTANCES_DIR="$TMP" RECONF=0
    RUNTIME=""; PORT=""; PRESET=""; PROVIDER=""; PROJECTS=""
    BIND="$1"; AI_FROM_HOST="$2"; AI_PROVIDERS="$3"; SOCKET_FLAG="$4"; SOCKET="$5"
    . "$HERE/../lib/module-preset.sh"; . "$HERE/../lib/setup-kept.sh"; load_existing harden
    printf '%s|%s|%s|%s|%s' "$BIND" "$AI_FROM_HOST" "$AI_PROVIDERS" "$SOCKET" "$CUSTOM_LINES" )
}
LE2="$(LEH 127.0.0.1 "" "" 0 0)"
check "no flags: bind/ai-from-host/ai-providers/docker-socket from the file" \
  '[ "$(unset -f cut; printf "%s" "$LE2" | cut -d"|" -f1-4)" = "127.0.0.2|all|codex,pi|1" ]'
check "no flags: the hardening switch of the file is carried into CUSTOM_LINES" \
  'printf "%s" "$LE2" | grep "AUTOMATION_HARDENING_SEALING=true" >/dev/null'
LE3="$(LEH 0.0.0.0 programs "" 1 1)"
check "a flag wins over the file (bind, ai-from-host, docker-socket)" \
  '[ "$(unset -f cut; printf "%s" "$LE3" | cut -d"|" -f1-4)" = "0.0.0.0|programs|codex,pi|1" ]'

echo "10) bash 3.2 hygiene"
check "no bash-4-only syntax (code)"                    '! grep -v "^[[:space:]]*#" "$SETUP" | grep -nE "declare -g?A|mapfile|readarray|\\$\\{[A-Za-z_]+(,,|\\^\\^)\\}" >/dev/null'

# Readers that stop before the end of the input — grep -q/-m at the first hit, head after N lines,
# sed with q —, let the writer before the | run into SIGPIPE, and pipefail turns the pipeline into an
# error: flaky, depending on output and load (e7ff6cc2, 38f30352). In the script tests therefore
# "| grep … >/dev/null" and "| sed -n 1p" (reads to the end); this line keeps the patterns from all
# suites out of pipefail.
early_readers() {
  local f
  for f in "$HERE"/*.sh; do
    grep -q pipefail "$f" || continue
    grep -nE '(^|[^|])[|][[:space:]]*(grep[[:space:]]+(-[[:alnum:]-]+[[:space:]]+)*-[[:alpha:]]*[qm]|head([[:space:]]|$)|sed[[:space:]][^|]*[0-9$/]q)' "$f" \
      | grep -vE '^[0-9]+:[[:space:]]*#' | sed "s|^|$(basename "$f"):|"
  done
}
check "no early-closing reader (grep -q/-m, head, sed q) under pipefail in scripts/tests (SIGPIPE)" '[ -z "$(early_readers)" ] || { early_readers | sed "s/^/         /"; false; }'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
