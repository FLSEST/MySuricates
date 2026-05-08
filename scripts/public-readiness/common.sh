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
run_limited() { "$@" 2>&1 | redact | head -n "$MAX_LINES"; return ${PIPESTATUS[0]}; }
cd_root() { cd "$ROOT" || exit 2; }
