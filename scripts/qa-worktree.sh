#!/usr/bin/env bash
# qa-worktree.sh — provides an isolated git worktree for build/test workflows.
#
# Why: the dev backend runs with `-cp target/classes` (a directory, not a JAR) and lazily
# loads classes from disk for its entire lifetime. A `mvn test` in the same checkout
# rewrites target/classes underneath the running JVM -> NoClassDefFoundError -> workflow
# threads die silently -> ghost runs. See docs/plans/2026-07-27-ghost-run-root-cause-fixes.md
#
# Usage:
#   scripts/qa-worktree.sh prepare   # create/sync the worktree, print its path on stdout
#   scripts/qa-worktree.sh path      # print the path only, change nothing

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
WT_ROOT="${MYSURICATES_QA_WORKTREE:-$HOME/.mysuricates/qa-worktree}"
WT="$WT_ROOT/$(basename "$REPO_ROOT")"

# Guard against recursion: never treat the QA worktree itself as the source checkout.
case "$REPO_ROOT/" in
  "$WT_ROOT"/*)
    echo "Refusing to run: REPO_ROOT ($REPO_ROOT) is inside the QA worktree root ($WT_ROOT)." >&2
    exit 2
    ;;
esac

cmd_path() {
  echo "$WT"
}

cmd_prepare() {
  local sha
  sha="$(git -C "$REPO_ROOT" rev-parse HEAD)"

  mkdir -p "$WT_ROOT"
  git -C "$REPO_ROOT" worktree prune

  if [ ! -e "$WT/.git" ]; then
    echo "Creating QA worktree at $WT" >&2
    git -C "$REPO_ROOT" worktree add --detach "$WT" "$sha" >&2
  else
    echo "Syncing QA worktree $WT -> $sha" >&2
    git -C "$WT" checkout --detach "$sha" >&2
    git -C "$WT" reset --hard "$sha" >&2
    # Keep build caches; drop everything else that is untracked.
    git -C "$WT" clean -fdq -e target -e node_modules -e data -e .qa-npm-lock-hash
  fi

  # Reinstall node_modules only when the lockfile actually changed (npm ci is expensive).
  local fe="$WT/ai-automation-frontend"
  if [ -f "$fe/package-lock.json" ]; then
    local lock_hash stamp
    lock_hash="$(sha256sum "$fe/package-lock.json" | cut -d' ' -f1)"
    stamp="$WT/.qa-npm-lock-hash"
    if [ ! -d "$fe/node_modules" ] || [ "$(cat "$stamp" 2>/dev/null || true)" != "$lock_hash" ]; then
      echo "Installing frontend dependencies (lockfile changed or node_modules missing)" >&2
      (cd "$fe" && npm ci --no-audit --no-fund) >&2
      echo "$lock_hash" > "$stamp"
    fi
  fi

  echo "$WT"
}

case "${1:-prepare}" in
  prepare) cmd_prepare ;;
  path)    cmd_path ;;
  *)
    echo "Usage: $0 {prepare|path}" >&2
    exit 2
    ;;
esac
