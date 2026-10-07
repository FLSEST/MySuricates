#!/usr/bin/env bash
set -uo pipefail
ROOT="${PUBLIC_READINESS_ROOT:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
MAX_LINES="${PUBLIC_READINESS_MAX_LINES:-240}"
redact() {
  sed -E \
    -e 's/(api[_-]?key|token|secret|password|passwd|pwd|bearer|authorization|cookie)([[:space:]]*[:=][[:space:]]*)[^[:space:],;}]+/\1\2[REDACTED]/Ig' \
    -e 's/(-----BEGIN [A-Z ]+PRIVATE KEY-----)[^-]*(-----END [A-Z ]+PRIVATE KEY-----)/\1[REDACTED]\2/g' \
    -e 's#(https?://)[^/@[:space:]]+:[^/@[:space:]]+@#\1[REDACTED]@#g'
}
section() { printf '\n## %s\n' "$1"; }
inventory_section() { printf '\n## %s [deterministic]\n' "$1"; }
tool_section() {
  local name="$1"
  local tool="${2:-}"
  printf '\n## %s [non-deterministic, tool-dependent]\n' "$name"
  if [ -n "$tool" ]; then
    if command -v "$tool" >/dev/null 2>&1; then
      local version
      # Some tools (Maven) colour their version line even without a terminal; strip ANSI escape sequences.
      version=$("$tool" --version 2>/dev/null | head -n 1 | sed -E $'s/\033\\[[0-9;]*[A-Za-z]//g' | redact || echo "unknown")
      printf 'tool: %s\ntool_available: yes\ntool_version: %s\n' "$tool" "$version"
    else
      printf 'tool: %s\ntool_available: no\n' "$tool"
    fi
  fi
}

# Portable grep replacement. Workflow-block shells often lack ripgrep, so we
# wrap GNU grep with sensible exclusions used across the public-readiness scripts.
# No -n by default: callers add -n / -h / -c / -i / -o themselves.
# Usage: pgrep [extra grep flags] PATTERN PATH...
pgrep() {
  grep -rE --binary-files=without-match -I \
    --exclude-dir=node_modules --exclude-dir=target --exclude-dir=dist \
    --exclude-dir=build --exclude-dir=data --exclude-dir=.git \
    --exclude-dir=.output --exclude-dir=.tmp --exclude-dir=screenshots \
    --exclude-dir=.playwright-mcp --exclude-dir=.idea --exclude-dir=.vscode \
    --exclude='*.png' --exclude='*.jpg' --exclude='*.gif' --exclude='*.webp' \
    --exclude='*.lock' --exclude='*.min.js' --exclude='*.map' \
    "$@" 2>/dev/null
}
# Total match count across files. Returns "0" on no match.
pgrep_count_total() {
  pgrep "$@" | wc -l | tr -d ' '
}

run_limited() { "$@" 2>&1 | redact | head -n "$MAX_LINES"; return ${PIPESTATUS[0]}; }
cd_root() { cd "$ROOT" || exit 2; }
