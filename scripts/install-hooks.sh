#!/usr/bin/env bash
#
# Installs the git hooks versioned in the repo via core.hooksPath.
# Run once per clone.
#
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

git config core.hooksPath scripts/git-hooks
chmod +x scripts/git-hooks/*

echo "Git hooks installed:"
echo "  core.hooksPath = $(git config --get core.hooksPath)"
ls -1 scripts/git-hooks/ | grep -v '\.sample$' | sed 's/^/  - /'
