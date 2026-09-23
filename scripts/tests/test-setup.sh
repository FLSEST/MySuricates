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

# Eigener PATH: Stub-Werkzeuge, dazu nur die Grundwerkzeuge des Systems (keine echten KI-CLIs)
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
stub npm 'echo 11.19.0'
stub docker 'case "$1" in --version) echo "Docker version 29.1.3, build x";; info) exit 0;; esac'
stub apt-get 'exit 0'
stub codex 'exit 0'

INST="$TMP/inst"
# XDG_CONFIG_HOME gehoert mitgesetzt: setup.sh:35 loest AI_ENV ueber
# ${XDG_CONFIG_HOME:-$HOME/.config} auf. Erbt der Lauf die Variable, schreiben die Tests
# nach $TMP/home/.config und setup.sh liest woanders — der Token wird nie gefunden. Auf
# dem CI-Runner ist sie gesetzt, lokal meist nicht (Lauf 35912021976: genau diese 2 rot).
run() {  # setup.sh without a terminal; stdin closed
  ( HOME="$TMP/home" PATH="$TMP/bin:$TMP/tools" XDG_DATA_HOME="$TMP/data" XDG_STATE_HOME="$TMP/state" \
    XDG_CONFIG_HOME="$TMP/home/.config" \
    MYSURICATES_INSTANCES_DIR="$INST" TERM=dumb; export HOME PATH XDG_DATA_HOME XDG_STATE_HOME XDG_CONFIG_HOME MYSURICATES_INSTANCES_DIR TERM
    bash "$SETUP" "$@" < /dev/null ) > "$TMP/out" 2>&1
  echo $?
}
val() { grep "^$2=" "$INST/$1.env" 2>/dev/null | cut -d= -f2-; }
# Erster freier Port ab BASIS — der Installer weicht belegten Ports aus (hier laeuft evtl. eine Dev-Instanz)
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

echo "3) one instance on this machine"
rm -rf "$INST"
check "--yes --dry-run --runtime host → exit 0"         '[ "$(run --yes --dry-run --runtime host)" = 0 ]'
check "instance file for host"                          '[ "$(val main MYSURICATES_RUNTIME)" = host ] && [ "$(val main MYSURICATES_PORT)" = "$(firstfree 9090)" ] && [ "$(val main MYSURICATES_PRESET)" = core ]'
check "default provider is the one installed (codex)"   '[ "$(val main AUTOMATION_AI_DEFAULT_PROVIDER)" = CODEX ]'
check "dry run names the start command"                 'out | grep "instance.sh up --name main" >/dev/null'
check "host: no container switches for the AI"          '[ -z "$(val main MYSURICATES_AI_FROM_HOST)" ]'

echo "4) one instance in a container (default)"
rm -rf "$INST"
check "--yes --dry-run → container"                     '[ "$(run --yes --dry-run)" = 0 ] && [ "$(val main MYSURICATES_RUNTIME)" = container ]'
check "projects: folder above the clone"                '[ "$(val main MYSURICATES_PROJECTS)" = "$(cd "$HERE/../../.." && pwd)" ]'
check "docker socket off by default"                    '[ "$(val main MYSURICATES_DOCKER_SOCKET)" = 0 ]'
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

echo "7) an existing setup is kept unless asked"
printf 'MYSURICATES_RUNTIME=host\nMYSURICATES_PORT=9777\nMYSURICATES_PRESET=full\n' > "$INST/main.env"
check "kept without --reconfigure"                      '[ "$(run --yes --dry-run --runtime host)" = 0 ] && [ "$(val main MYSURICATES_PORT)" = 9777 ]'
check "--preset core --force changes only the preset"   '[ "$(run --yes --dry-run --runtime host --preset core --force)" = 0 ] && [ "$(val main MYSURICATES_PRESET)" = core ] && [ "$(val main MYSURICATES_PORT)" = 9777 ]'
check "--reconfigure rewrites it"                       '[ "$(run --yes --dry-run --runtime host --reconfigure)" = 0 ] && [ "$(val main MYSURICATES_PORT)" = "$(firstfree 9090)" ]'

echo "8) language"
rm -rf "$INST"
run --yes --dry-run --runtime host >/dev/null
check "English by default"                              'out | grep -E "Mode|Checks" >/dev/null'
rm -rf "$INST"
run --yes --dry-run --runtime host --lang de >/dev/null
check "German with --lang de"                           'out | grep -E "Modus|Prüfung" >/dev/null'
# Die drei Befehle im Abschluss-Kasten beginnen in derselben Spalte — in jeder Sprache
# Spalte per bash (zaehlt Zeichen); busybox-awk index() zaehlt Bytes und verrutscht an „Ä“
cols() { out | grep -E "instance.sh key --name|up\|down\|status --name|setup.sh · deploy" | while IFS= read -r l; do l="${l%%scripts/*}"; echo "${#l}"; done | sort -u | wc -l | tr -d " "; }
check "done box: commands aligned (de)"                  '[ "$(cols)" = 1 ]'
rm -rf "$INST"; run --yes --dry-run --runtime host >/dev/null
check "done box: commands aligned (en)"                  '[ "$(cols)" = 1 ]'

echo "8b) menus fit the panel — nothing cut off (pseudo terminal, stub tools)"
# Abgeschnitten = „…“ direkt vor dem rechten Rahmen; „Eigene Auswahl…“ mitten in der Zeile ist gewollt
cut() { sed "s/$(printf "\033")\[[0-9;?]*[A-Za-z]//g" "$1" | tr -d "\r" | grep -E "…[[:space:]]*│[[:space:]]*$" >/dev/null; }
pty_setup() {  # SPRACHE TASTEN FLAGS… → Mitschnitt in $TMP/menu
  local l="$1" keys="$2"; shift 2
  rm -rf "$INST"
  printf "$keys" | script -qec "stty cols 120 rows 50; env HOME='$TMP/home' PATH='$TMP/bin:$TMP/tools' XDG_DATA_HOME='$TMP/data' XDG_STATE_HOME='$TMP/state' XDG_CONFIG_HOME='$TMP/home/.config' MYSURICATES_INSTANCES_DIR='$INST' TERM=xterm-256color bash '$SETUP' --dry-run --lang $l $*" /dev/null > "$TMP/menu" 2>&1
}
if command -v script >/dev/null 2>&1 && script -qec true /dev/null >/dev/null 2>&1; then
  for l in en de; do
    pty_setup "$l" q
    check "mode menu shown and complete ($l)"             'grep -E "(Step|Schritt) 2 (of|von)" "$TMP/menu" >/dev/null && ! cut "$TMP/menu"'
    pty_setup "$l" q --runtime host
    check "module menu shown and complete ($l)"           'grep -E "(Step|Schritt) 4 (of|von)" "$TMP/menu" >/dev/null && ! cut "$TMP/menu"'
    pty_setup "$l" q --runtime container --preset core --provider codex
    sed "s/$(printf "\033")\[[0-9;?]*[A-Za-z]//g" "$TMP/menu" | tr -d "\r" | grep -A4 -E "(in den Container|into the container)\?" > "$TMP/ai-menu"
    check "AI access menu shown and complete ($l)"       '[ -s "$TMP/ai-menu" ] && ! cut "$TMP/ai-menu"'
  done
else
  echo "  skip (no usable script command)"
fi

echo "9) the API key hint never points to the log (bc6d1902)"
check "no 'backend log' in the installer"               '! grep -niE "backend.?log|AUTO-GENERATED" "$SETUP" >/dev/null'
FE="$HERE/../../ai-automation-frontend/src/i18n"
check "login dialog does not send users to the log"     '! grep -hE "^ *authHint:" "$FE/de.ts" "$FE/en.ts" | grep -iE "log|AUTO-GENERATED" >/dev/null'
check "dev starter does not send users to the log"      '! grep -n "siehe Backend-Log" "$HERE/../start-autotrigger-dev.sh" >/dev/null'

echo "10) bash 3.2 hygiene"
check "no bash-4-only syntax (code)"                    '! grep -v "^[[:space:]]*#" "$SETUP" | grep -nE "declare -g?A|mapfile|readarray|\\$\\{[A-Za-z_]+(,,|\\^\\^)\\}" >/dev/null'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
