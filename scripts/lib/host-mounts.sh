#!/usr/bin/env bash
# Compose override with exactly what THIS machine gives a MySuricates container, for every container
# way of running it: deploy/compose.yml via scripts/instance.sh, the dev container of
# start-autotrigger-dev.sh and Stable/Test via scripts/staging.sh. This file is the one place that
# decides what comes from the host (TODO fc9de67b, 262948a4).
#
# What comes from the host is chosen per instance (MYSURICATES_AI_FROM_HOST, decision of 2026-09-23):
#   programs  only the AI CLIs, read-only. Every instance logs in on its own and keeps that login on
#             its data volume.
#   login     the CLIs plus exactly one login per provider (default). Codex and Pi write their
#             auth.json in place, so that single file is shared read-write. Claude replaces its
#             .credentials.json on every refresh (temp file, then rename): a single-file mount would
#             keep the old inode and go stale, so Claude gets a long-lived token from
#             `claude setup-token` via ~/.config/mysuricates/ai.env instead.
#   all       whole provider directories read-write, as before 262948a4. Settings, hooks, plugins,
#             memory and transcripts of the host are then readable and writable in the container.
# With programs and login every provider keeps its own directory on the data volume
# (/app/data/providers/<id>), so nothing the CLIs write there reaches the host.
#
# Linux: the CLIs themselves come from the host (decision of 2026-09-21). macOS: a Mac binary does not run in a
# Linux container, so only logins are mounted; the CLIs have to come from the image (not built in yet,
# TODO 3dd32594). bash 3.2 (macOS).

HOST_MOUNTS_KNOWN_PROVIDERS="claude codex pi"
HOST_MOUNTS_PROVIDER_COUNT=0   # selected providers that can run: program, and a login unless `programs`
HOST_MOUNTS_READY=""           # their ids, space-separated
HOST_MOUNTS_MISSING=""         # "<id>:program" / "<id>:login" / "claude:token" (implausible) for the caller's warning

_hm_q() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
# Both append to the caller's locals (bash scoping is dynamic).
_hm_vol() { vols="${vols}      - \"$(_hm_q "$1")\"
"; }
_hm_env() { local e; for e in "$@"; do envs="${envs}      - \"$(_hm_q "$e")\"
"; done; }
# claude_token_from_file FILE — the CLAUDE_CODE_OAUTH_TOKEN value compose would read from FILE: the last
# such line wins, one pair of surrounding quotes comes off. Prints nothing when there is none.
claude_token_from_file() {
  local v
  [ -f "$1" ] || return 0
  v="$(grep -E '^CLAUDE_CODE_OAUTH_TOKEN=' "$1" | tail -n 1)"; v="${v#CLAUDE_CODE_OAUTH_TOKEN=}"
  case "$v" in
    \"*\") v="${v#\"}"; v="${v%\"}" ;;
    \'*\') v="${v#\'}"; v="${v%\'}" ;;
  esac
  printf '%s' "$v"
}

# claude_token_plausible VALUE — whether VALUE can be a token from `claude setup-token` at all (9bef7e8c):
# at least 40 characters, no whitespace, no angle brackets. Catches what a copied instruction leaves
# behind (<token>, …, a sentence). No fixed prefix: the token format belongs to Anthropic.
# Same rule in Java: com.automation.util.ClaudeOAuthToken.
claude_token_plausible() {
  case "$1" in *[[:space:]]*|*'<'*|*'>'*) return 1 ;; esac
  [ "${#1}" -ge 40 ]
}

_hm_has_claude_token() { claude_token_plausible "$(claude_token_from_file "$1")"; }

# host_mounts_yaml SERVICE PROJECTS_DIR DOCKER_SOCKET [AI_FROM_HOST] [AI_PROVIDERS] [SWITCH_SOURCE] [SWITCH_PREFIX] [LOGIN_COMMAND]
#   AI_FROM_HOST   programs | login (default) | all
#   AI_PROVIDERS   subset of claude,codex,pi, comma- or space-separated (default: all three)
#   SWITCH_SOURCE  where the switches of this instance are changed (a file, or how the starter is called)
#   SWITCH_PREFIX  prefix of the switch names there: MYSURICATES_, STABLE_, TEST_, AUTOTRIGGER_DEV_
#   LOGIN_COMMAND  the command that logs in inside THIS container, fixed text the caller already knows
#                  (e.g. "scripts/instance.sh login") — empty when the start path has none for this mode
#                  (AI_FROM_HOST=login or all: the login happens on the machine around the container, not
#                  in it). Never built from anything the caller does not already have as a literal string.
#   The last three are only shown on the settings page, which cannot change anything itself.
# Prints the override on stdout. An unknown mode or provider goes to stderr and returns 2.
host_mounts_yaml() {
  local service="$1" projects="${2:-}" socket="${3:-0}" access="${4:-login}" wanted="${5:-}"
  local switch_source="${6:-}" switch_prefix="${7:-}" login_command="${8:-}"
  local os="${HOST_MOUNTS_OS:-$(uname -s)}"
  local ai_env="${HOST_MOUNTS_AI_ENV:-${XDG_CONFIG_HOME:-$HOME/.config}/mysuricates/ai.env}"
  local home_in=/app/data/providers nm_in=/usr/lib/node_modules
  local vols="" envs="" envfile="" selected="" nm="" id p bin prog login why socket_on=0
  HOST_MOUNTS_PROVIDER_COUNT=0; HOST_MOUNTS_READY=""; HOST_MOUNTS_MISSING=""

  case "$access" in
    programs|login|all) ;;
    *) echo "host-mounts: unknown AI_FROM_HOST '$access' (programs|login|all)" >&2; return 2 ;;
  esac
  for id in $(printf '%s' "${wanted:-$HOST_MOUNTS_KNOWN_PROVIDERS}" | tr ',' ' '); do
    case " $HOST_MOUNTS_KNOWN_PROVIDERS " in
      *" $id "*) ;;
      *) echo "host-mounts: unknown provider '$id' (claude|codex|pi)" >&2; return 2 ;;
    esac
    case " $selected " in *" $id "*) ;; *) selected="${selected:+$selected }$id" ;; esac
  done

  # npm packages (Codex, Pi) always land at the path the image links point to, whatever the host's
  # npm prefix is, so the links in the image resolve.
  if [ "$os" = "Linux" ]; then
    nm="${HOST_MOUNTS_NODE_MODULES:-$(npm root -g 2>/dev/null || true)}"
    [ -n "$nm" ] || nm=/usr/lib/node_modules
  fi

  for id in $selected; do
    prog=""; login=""; why=login
    case "$id" in
      claude)
        if [ "$os" = "Linux" ] && [ -e "$HOME/.local/bin/claude" ]; then
          if [ "$access" = all ]; then
            for p in "$HOME/.local/bin" "$HOME/.local/share/claude"; do [ -e "$p" ] && _hm_vol "$p:$p:ro"; done
            prog=1
          else
            # Exactly the binary, not all of ~/.local/bin (auto-yes, pipx tools, ...).
            bin="$(readlink -f "$HOME/.local/bin/claude")"
            if [ -f "$bin" ]; then _hm_vol "$bin:/usr/local/bin/claude:ro"; prog=1; fi
          fi
        fi
        case "$access" in
          all)
            [ -f "$HOME/.claude.json" ] && _hm_vol "$HOME/.claude.json:/seed/.claude.json:ro"
            if [ -d "$HOME/.claude" ]; then _hm_vol "$HOME/.claude:$HOME/.claude"; login=1; fi ;;
          login)
            if _hm_has_claude_token "$ai_env"; then envfile="$ai_env"; login=1
            elif [ -n "$(claude_token_from_file "$ai_env")" ]; then why=token; fi
            _hm_env "CLAUDE_CONFIG_DIR=$home_in/claude" ;;
          programs)
            _hm_env "CLAUDE_CONFIG_DIR=$home_in/claude" ;;
        esac ;;
      codex)
        if [ -n "$nm" ] && [ -d "$nm/@openai/codex" ]; then _hm_vol "$nm/@openai/codex:$nm_in/@openai/codex:ro"; prog=1; fi
        case "$access" in
          all)
            if [ -d "$HOME/.codex" ]; then _hm_vol "$HOME/.codex:$HOME/.codex"; login=1; fi ;;
          login)
            if [ -f "$HOME/.codex/auth.json" ]; then _hm_vol "$HOME/.codex/auth.json:$home_in/codex/auth.json"; login=1; fi ;;
        esac
        [ "$access" = all ] || _hm_env "CODEX_HOME=$home_in/codex" "AUTOMATION_MODELCATALOG_CODEXHOME=$home_in/codex" ;;
      pi)
        if [ -n "$nm" ] && [ -d "$nm/@earendil-works/pi-coding-agent" ]; then
          _hm_vol "$nm/@earendil-works/pi-coding-agent:$nm_in/@earendil-works/pi-coding-agent:ro"; prog=1
        fi
        case "$access" in
          all)
            if [ -d "$HOME/.pi" ]; then _hm_vol "$HOME/.pi:$HOME/.pi"; login=1; fi ;;
          login)
            if [ -f "$HOME/.pi/agent/auth.json" ]; then _hm_vol "$HOME/.pi/agent/auth.json:$home_in/pi/auth.json"; login=1; fi ;;
        esac
        [ "$access" = all ] || _hm_env "PI_CODING_AGENT_DIR=$home_in/pi" "AUTOMATION_HOSTPI_CONFIGDIR=$home_in/pi" ;;
    esac
    if [ -z "$prog" ]; then
      HOST_MOUNTS_MISSING="${HOST_MOUNTS_MISSING:+$HOST_MOUNTS_MISSING }$id:program"
    elif [ "$access" != programs ] && [ -z "$login" ]; then
      HOST_MOUNTS_MISSING="${HOST_MOUNTS_MISSING:+$HOST_MOUNTS_MISSING }$id:$why"
    else
      HOST_MOUNTS_READY="${HOST_MOUNTS_READY:+$HOST_MOUNTS_READY }$id"
      HOST_MOUNTS_PROVIDER_COUNT=$((HOST_MOUNTS_PROVIDER_COUNT + 1))
    fi
  done

  # Projects: same path inside as outside, so project paths stored in the DB stay valid.
  # Never the whole home directory.
  if [ -n "$projects" ] && [ -d "$projects" ]; then
    projects="$(cd "$projects" && pwd)"
    if [ "$projects" != "$(cd "$HOME" && pwd)" ]; then _hm_vol "$projects:$projects"; fi
  fi

  # Docker socket only on request: it gives the instance full control over the host's Docker, and with
  # it every other boundary in this file can be walked around.
  if [ "$socket" = "1" ] && [ -S /var/run/docker.sock -o -n "${HOST_MOUNTS_ASSUME_SOCKET:-}" ]; then
    _hm_vol "/var/run/docker.sock:/var/run/docker.sock"; socket_on=1
  fi

  # What the settings page shows (read-only; changing it means editing SWITCH_SOURCE and restarting).
  _hm_env "DISABLE_AUTOUPDATER=1" "MYSURICATES_RUNTIME=container" "MYSURICATES_AI_FROM_HOST=$access" \
          "MYSURICATES_AI_PROVIDERS=$(printf '%s' "$selected" | tr ' ' ',')" "MYSURICATES_DOCKER_SOCKET=$socket_on"
  [ -n "$switch_source" ] && _hm_env "MYSURICATES_SWITCH_SOURCE=$switch_source"
  [ -n "$switch_prefix" ] && _hm_env "MYSURICATES_SWITCH_PREFIX=$switch_prefix"
  [ -n "$login_command" ] && _hm_env "MYSURICATES_LOGIN_COMMAND=$login_command"

  echo "# Generated by scripts/lib/host-mounts.sh — do not edit."
  echo "services:"
  echo "  ${service}:"
  if [ -z "$vols" ]; then
    echo "    volumes: []"
  else
    echo "    volumes:"
    printf '%s' "$vols"
  fi
  echo "    environment:"
  printf '%s' "$envs"
  if [ -n "$envfile" ]; then
    echo "    env_file:"
    echo "      - \"$(_hm_q "$envfile")\""
  fi
}

# host_mounts_missing_text — one line per missing piece, for the caller's warning (German, like the
# starters). Empty when every selected provider is ready.
host_mounts_missing_text() {
  local item id what
  for item in $HOST_MOUNTS_MISSING; do
    id="${item%%:*}"; what="${item#*:}"
    case "$id:$what" in
      claude:token) echo "  $id: CLAUDE_CODE_OAUTH_TOKEN in ~/.config/mysuricates/ai.env sieht nicht wie ein Token aus (Platzhalter, Leerzeichen oder unter 40 Zeichen) — neu mit \`claude setup-token\`" ;;
      claude:login) echo "  $id: keine Anmeldung fuer Container — einmal \`claude setup-token\`, den Token als CLAUDE_CODE_OAUTH_TOKEN=... nach ~/.config/mysuricates/ai.env (chmod 600)" ;;
      *:login)      echo "  $id: keine Anmeldedatei auf diesem Rechner gefunden (eine Anmeldung nur per Umgebungsvariable kommt nicht in den Container)" ;;
      *)            echo "  $id: Programm auf diesem Rechner nicht gefunden" ;;
    esac
  done
}
