#!/usr/bin/env bash
# Tests for scripts/lib/host-mounts.sh — the compose override that decides what a container gets from
# the host (MYSURICATES_AI_FROM_HOST programs|login|all, MYSURICATES_AI_PROVIDERS, socket, projects).
# Run: bash scripts/tests/test-host-mounts.sh   (exit 0 = all passed)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }
run() {  # $1 = OS, rest = host_mounts_yaml args; stderr goes to $TMP/err, rc to $TMP/rc
  local os="$1"; shift
  ( HOME="$TMP/home" HOST_MOUNTS_OS="$os" HOST_MOUNTS_NODE_MODULES="${NM_OVERRIDE-$TMP/nm}" HOST_MOUNTS_AI_ENV="$TMP/config/ai.env"
    export HOME HOST_MOUNTS_OS HOST_MOUNTS_AI_ENV
    if [ -n "$HOST_MOUNTS_NODE_MODULES" ]; then export HOST_MOUNTS_NODE_MODULES; else unset HOST_MOUNTS_NODE_MODULES; fi
    . "$HERE/../lib/host-mounts.sh"
    host_mounts_yaml "$@" 2>"$TMP/err"; rc=$?; echo "$rc" > "$TMP/rc"
    echo "#count=$HOST_MOUNTS_PROVIDER_COUNT"; echo "#ready=$HOST_MOUNTS_READY"; echo "#missing=$HOST_MOUNTS_MISSING"
    host_mounts_missing_text | sed 's/^/#text=/' )
}
has()   { grep -qF -- "$1" <<<"$OUT"; }   # literal substring
line()  { grep -qxF -- "$1" <<<"$OUT"; }  # exact line
mkdir -p "$TMP/home" "$TMP/config"

echo "1) nothing installed"
OUT="$(run Linux svc "" 0)"
check "valid empty override"            'grep -q "^services:" <<<"$OUT" && grep -q "^  svc:" <<<"$OUT" && has "volumes: []"'
check "default mode is login"           'line "      - \"MYSURICATES_AI_FROM_HOST=login\""'
check "all three providers selected"    'line "      - \"MYSURICATES_AI_PROVIDERS=claude,codex,pi\""'
check "no provider counted"             'has "#count=0"'
check "missing programs reported"       'has "#missing=claude:program codex:program pi:program"'

# Fixtures: a native claude with its version store, one unrelated tool in ~/.local/bin, the provider
# homes with login files AND the things that must stay on the host (settings, config, instructions).
mkdir -p "$TMP/home/.local/bin" "$TMP/home/.local/share/claude/versions" "$TMP/home/.claude/projects" \
         "$TMP/home/.codex/rules" "$TMP/home/.pi/agent" "$TMP/nm/@openai/codex" "$TMP/nm/@earendil-works/pi-coding-agent"
printf '#!/bin/sh\n' > "$TMP/home/.local/share/claude/versions/9.9.9"; chmod +x "$TMP/home/.local/share/claude/versions/9.9.9"
ln -s ../share/claude/versions/9.9.9 "$TMP/home/.local/bin/claude"
printf '#!/bin/sh\n' > "$TMP/home/.local/bin/auto-yes"
echo '{}' > "$TMP/home/.claude.json"; echo '{}' > "$TMP/home/.claude/.credentials.json"; echo '{}' > "$TMP/home/.claude/settings.json"
echo '{}' > "$TMP/home/.codex/auth.json"; echo 'model = "x"' > "$TMP/home/.codex/config.toml"; echo 'x' > "$TMP/home/.codex/AGENTS.md"
echo '{}' > "$TMP/home/.pi/agent/auth.json"; echo '{}' > "$TMP/home/.pi/agent/settings.json"
CLAUDE_BIN="$(cd "$TMP/home/.local/share/claude/versions" && pwd)/9.9.9"

echo "2) login (default): programs read-only, exactly one login file per provider"
OUT="$(run Linux svc "" 0)"
check "claude binary as a single file"  'line "      - \"$CLAUDE_BIN:/usr/local/bin/claude:ro\""'
check "not all of ~/.local/bin"         '! has "$TMP/home/.local/bin:" && ! has "auto-yes" && ! has "$TMP/home/.local/share/claude:"'
check "nothing of ~/.claude"            '! has "$TMP/home/.claude" && ! has "/seed/"'
check "codex: only auth.json, rw"       'line "      - \"$TMP/home/.codex/auth.json:/app/data/providers/codex/auth.json\""'
check "pi: only auth.json, rw"          'line "      - \"$TMP/home/.pi/agent/auth.json:/app/data/providers/pi/auth.json\""'
check "no provider dirs, no config"     '! has "$TMP/home/.codex:" && ! has "$TMP/home/.pi:" && ! has "config.toml" && ! has "AGENTS.md" && ! has "settings.json"'
check "packages at the fixed path, ro"  'line "      - \"$TMP/nm/@openai/codex:/usr/lib/node_modules/@openai/codex:ro\"" && line "      - \"$TMP/nm/@earendil-works/pi-coding-agent:/usr/lib/node_modules/@earendil-works/pi-coding-agent:ro\""'
check "private provider homes"          'has "\"CLAUDE_CONFIG_DIR=/app/data/providers/claude\"" && has "\"CODEX_HOME=/app/data/providers/codex\"" && has "\"PI_CODING_AGENT_DIR=/app/data/providers/pi\""'
check "catalog follows the homes"       'has "\"AUTOMATION_MODELCATALOG_CODEXHOME=/app/data/providers/codex\"" && has "\"AUTOMATION_HOSTPI_CONFIGDIR=/app/data/providers/pi\""'
check "no auto-update, runtime marked"  'has "\"DISABLE_AUTOUPDATER=1\"" && has "\"MYSURICATES_RUNTIME=container\""'
check "claude without token: no login"  '! has "env_file:" && has "#count=2" && has "#missing=claude:login"'
check "hint names claude setup-token"   'has "#text=  claude:" && has "claude setup-token"'

echo "3) login with a token in ai.env"
printf 'CLAUDE_CODE_OAUTH_TOKEN=secret-test-token-0123456789-abcdefghijklmnop\n' > "$TMP/config/ai.env"
OUT="$(run Linux svc "" 0)"
check "token file passed as env_file"   'grep -q "^    env_file:" <<<"$OUT" && line "      - \"$TMP/config/ai.env\""'
check "token value never in override"   '! has "secret-test-token"'
check "all three ready"                 'has "#count=3" && has "#ready=claude codex pi" && has "#missing=" && ! grep -q "^#missing=." <<<"$OUT"'
printf 'SOMETHING_ELSE=1\n' > "$TMP/config/ai.env"
OUT="$(run Linux svc "" 0)"
check "no token line: no env_file"      '! has "env_file:" && has "#missing=claude:login"'
printf 'CLAUDE_CODE_OAUTH_TOKEN=secret-test-token-0123456789-abcdefghijklmnop\n' > "$TMP/config/ai.env"

echo "3b) only a plausible token counts as a login (9bef7e8c)"
tok() { printf '%s\n' "$@" > "$TMP/config/ai.env"; OUT="$(run Linux svc "" 0)"; }
tok 'CLAUDE_CODE_OAUTH_TOKEN=<token>'
check "placeholder: no env_file, reason token" '! has "env_file:" && has "#count=2" && has "#missing=claude:token"'
check "placeholder: hint says why"             'has "sieht nicht wie ein Token aus" && has "claude setup-token"'
tok 'CLAUDE_CODE_OAUTH_TOKEN=abc123'
check "too short: reason token"                '! has "env_file:" && has "#missing=claude:token"'
tok 'CLAUDE_CODE_OAUTH_TOKEN=sk ant oat token with some words in it right'
check "spaces: reason token"                   '! has "env_file:" && has "#missing=claude:token"'
tok 'CLAUDE_CODE_OAUTH_TOKEN='
check "empty value: plain missing login"       '! has "env_file:" && has "#missing=claude:login"'
tok 'CLAUDE_CODE_OAUTH_TOKEN="secret-test-token-0123456789-abcdefghijklmnop"'
check "double quotes come off"                 'grep -q "^    env_file:" <<<"$OUT" && has "#count=3"'
tok "CLAUDE_CODE_OAUTH_TOKEN='secret-test-token-0123456789-abcdefghijklmnop'"
check "single quotes come off"                 'grep -q "^    env_file:" <<<"$OUT" && has "#count=3"'
tok 'CLAUDE_CODE_OAUTH_TOKEN=<token>' 'CLAUDE_CODE_OAUTH_TOKEN=secret-test-token-0123456789-abcdefghijklmnop'
check "last line wins (valid last)"            'grep -q "^    env_file:" <<<"$OUT" && has "#count=3"'
tok 'CLAUDE_CODE_OAUTH_TOKEN=secret-test-token-0123456789-abcdefghijklmnop' 'CLAUDE_CODE_OAUTH_TOKEN=<token>'
check "last line wins (placeholder last)"      '! has "env_file:" && has "#missing=claude:token"'
plaus() { ( . "$HERE/../lib/host-mounts.sh"; claude_token_plausible "$1" ); }
check "39 characters are not enough"           '! plaus "$(printf "%039d" 0)"'
check "40 characters are"                      'plaus "$(printf "%040d" 0)"'
check "an ellipsis is not a token"             '! plaus "…"'
printf 'CLAUDE_CODE_OAUTH_TOKEN=secret-test-token-0123456789-abcdefghijklmnop\n' > "$TMP/config/ai.env"

echo "4) programs: no login from the host at all"
OUT="$(run Linux svc "" 0 programs)"
check "no login files, no token"        '! has "auth.json" && ! has "env_file:" && ! has ".credentials.json"'
check "programs still mounted"          'has "/usr/local/bin/claude:ro" && has "/usr/lib/node_modules/@openai/codex:ro"'
check "private homes for own logins"    'has "\"CLAUDE_CONFIG_DIR=/app/data/providers/claude\"" && has "\"CODEX_HOME=/app/data/providers/codex\""'
check "ready = programs present"        'has "#count=3" && has "\"MYSURICATES_AI_FROM_HOST=programs\""'

echo "5) all: the whole provider directories, as before"
OUT="$(run Linux svc "" 0 all)"
check "binary dirs ro"                  'line "      - \"$TMP/home/.local/bin:$TMP/home/.local/bin:ro\"" && line "      - \"$TMP/home/.local/share/claude:$TMP/home/.local/share/claude:ro\""'
check "claude.json seeded read-only"    'line "      - \"$TMP/home/.claude.json:/seed/.claude.json:ro\""'
check "provider dirs rw"                'line "      - \"$TMP/home/.claude:$TMP/home/.claude\"" && line "      - \"$TMP/home/.codex:$TMP/home/.codex\"" && line "      - \"$TMP/home/.pi:$TMP/home/.pi\""'
check "host paths, no private homes"    '! has "CODEX_HOME=" && ! has "CLAUDE_CONFIG_DIR=" && ! has "env_file:"'
check "all ready"                       'has "#count=3"'

echo "6) provider selection"
OUT="$(run Linux svc "" 0 login claude)"
check "only claude"                     'has "/usr/local/bin/claude:ro" && ! has "@openai" && ! has "pi-coding-agent" && ! has "CODEX_HOME=" && ! has ".codex"'
check "selection shown"                 'line "      - \"MYSURICATES_AI_PROVIDERS=claude\""'
OUT="$(run Linux svc "" 0 all "codex, pi")"
check "codex,pi: nothing of claude"     '! has ".local" && ! has ".claude" && has "$TMP/home/.codex:" && has "#count=2"'
OUT="$(run Linux svc "" 0 login "claude,claude")"
check "duplicates collapse"             'line "      - \"MYSURICATES_AI_PROVIDERS=claude\"" && [ "$(grep -c "/usr/local/bin/claude" <<<"$OUT")" -eq 1 ]'

echo "7) invalid values are refused"
OUT="$(run Linux svc "" 0 everything)"
check "unknown mode: rc 2 + message"    '[ "$(cat "$TMP/rc")" = 2 ] && grep -q "unknown AI_FROM_HOST" "$TMP/err" && ! has "services:"'
OUT="$(run Linux svc "" 0 login "claude,gemini")"
check "unknown provider: rc 2"          '[ "$(cat "$TMP/rc")" = 2 ] && grep -q "unknown provider .gemini." "$TMP/err"'

echo "8) macOS: logins only, never host binaries"
OUT="$(run Darwin svc "" 0)"
check "no binaries on macOS"            '! has "/usr/local/bin/claude" && ! has "@openai" && ! has "/nm/"'
check "login files still mounted"       'has "$TMP/home/.codex/auth.json:/app/data/providers/codex/auth.json" && has "env_file:"'
check "programs reported missing"       'has "#count=0" && has "claude:program"'

echo "9) npm prefix from npm root -g"
mkdir -p "$TMP/nm2/@openai/codex" "$TMP/bin"
printf '#!/bin/sh\n[ "$1 $2" = "root -g" ] && echo "%s"\n' "$TMP/nm2" > "$TMP/bin/npm"; chmod +x "$TMP/bin/npm"
OUT="$(PATH="$TMP/bin:$PATH" NM_OVERRIDE="" run Linux svc "" 0 login codex)"
check "other prefix, same target"       'line "      - \"$TMP/nm2/@openai/codex:/usr/lib/node_modules/@openai/codex:ro\""'

echo "10) projects, docker socket, switch file"
export HOST_MOUNTS_ASSUME_SOCKET=1   # CI/probe may have no real socket
mkdir -p "$TMP/projects"
OUT="$(run Linux svc "$TMP/projects" 1 login "" "/etc/demo \"x\".env" STABLE_)"
check "projects mounted at same path"   'line "      - \"$TMP/projects:$TMP/projects\""'
check "socket when asked, and shown"    'line "      - \"/var/run/docker.sock:/var/run/docker.sock\"" && has "\"MYSURICATES_DOCKER_SOCKET=1\""'
check "switch source shown, quoted"     'line "      - \"MYSURICATES_SWITCH_SOURCE=/etc/demo \\\"x\\\".env\""'
check "switch prefix shown"             'line "      - \"MYSURICATES_SWITCH_PREFIX=STABLE_\""'
check "no login command: param left empty" '! has "MYSURICATES_LOGIN_COMMAND"'
OUT="$(run Linux svc "$TMP/projects" 1 login "" "/etc/demo \"x\".env" STABLE_ "scripts/staging.sh login stable")"
check "login command shown"             'line "      - \"MYSURICATES_LOGIN_COMMAND=scripts/staging.sh login stable\""'
OUT="$(run Linux svc "$TMP/projects" 0)"
check "no socket by default"            '! has "docker.sock" && has "\"MYSURICATES_DOCKER_SOCKET=0\"" && ! has "MYSURICATES_SWITCH_" && ! has "MYSURICATES_LOGIN_COMMAND"'
check "missing projects dir is skipped" '! grep -q "/nope" <<<"$(run Linux svc "$TMP/nope" 0)"'
check "whole HOME is refused"           '! grep -qF -- "\"$TMP/home:$TMP/home\"" <<<"$(run Linux svc "$TMP/home" 0)"'

echo "11) bash 3.2 hygiene"
check "no bash-4-only syntax"           '! grep -nE "declare -g?A|mapfile|readarray|\\$\\{[A-Za-z_]+(,,|\\^\\^)\\}" "$HERE/../lib/host-mounts.sh"'

echo; echo "passed=$PASS failed=$FAIL"; [ "$FAIL" -eq 0 ]
