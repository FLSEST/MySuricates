#!/usr/bin/env bash
#
# Public-Hygiene-Gate
#
# Hard gate: tracked files in ignored paths and forbidden patterns
# cause exit 1. Runs locally and in GitHub Actions (annotations are
# CI-friendly).
#
# Usage:  bash scripts/ci/public-hygiene.sh
# Exit:   0 = clean, 1 = violations found
#
set -euo pipefail

FAIL=0
COUNT=0

annotate() {
  # $1=file, $2=line, $3=message
  echo "::error file=${1},line=${2}::${3}"
  FAIL=1
  COUNT=$((COUNT + 1))
}

# ---------------------------------------------------------------------------
# Check 1 — tracked files in root-anchored ignored paths
# ---------------------------------------------------------------------------
# These directories are listed in .gitignore but git would still track files
# committed before the ignore rule was added. The review/ vs reports/ incident
# is exactly this class of bug.
check_tracked_paths() {
  local forbidden=(
    review
    .output
    reports
    portfolio
    dashboard
    presentation
    ebook
    prototype
    output
    todo-sdk
  )
  local d f
  for d in "${forbidden[@]}"; do
    while IFS= read -r f; do
      [ -z "$f" ] && continue
      annotate "$f" 1 "tracked file in ignored path '${d}/' — run: git rm --cached '${f}'"
    done < <(git ls-files -- "${d}/" 2>/dev/null || true)
  done
}

# ---------------------------------------------------------------------------
# Check 2 — forbidden literal patterns anywhere in tracked files
# ---------------------------------------------------------------------------
# Self-exclude so this script's own pattern list does not trip the gate.
check_forbidden_patterns() {
  local patterns=(
    "/home/flo|absolute developer path"
    "test-e2e-key|hardcoded test API key string"
    "ebf4f50c-20c2-4c35-a84a-a0b350124523|internal project UUID"
  )
  local entry pat desc file line
  for entry in "${patterns[@]}"; do
    pat="${entry%%|*}"
    desc="${entry##*|}"
    while IFS=: read -r file line _; do
      [ -z "$file" ] && continue
      annotate "$file" "$line" "${desc}: '${pat}'"
    done < <(
      git grep -nI -F -- "$pat" \
        ':!scripts/ci/public-hygiene.sh' \
        2>/dev/null || true
    )
  done
}

# ---------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------
check_tracked_paths
check_forbidden_patterns

if [ "$FAIL" -ne 0 ]; then
  echo ""
  echo "Public-Hygiene-Gate FAILED — ${COUNT} violation(s)."
  echo "Fix: remove tracked artifacts (git rm --cached) and replace literals."
  exit 1
fi

echo "Public-Hygiene-Gate OK"
