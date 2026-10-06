#!/usr/bin/env bash
# arch-guard.sh -- runs the three architecture ratchet tests that a targeted
# -Dtest selection easily forgets (TODO 46eb35aa): PackageCycleRatchetTest,
# LayerRuleTest and SourceSizeRatchetTest measure the compiled classes via jdeps and file sizes --
# they are useful exactly when they run early, not
# only at the full integration checkpoint.
#
# Whoever adds a new class, moves a class between packages or renames a package
# runs this script in addition to their own targeted test selection
# (see docs/entwicklung.md -> section on targeted test runs).
#
# Time budget: the three classes themselves need a few seconds; on top comes the
# compile step (fast incremental build if target/ exists, otherwise the
# full compile). The script reports the actual runtime at the end.
#
# Run: bash scripts/tests/arch-guard.sh   (exit 0 = all three tests green)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
BACKEND="$REPO/ai-automation-backend"

if [ ! -d "$BACKEND" ]; then
  echo "arch-guard: Backend-Verzeichnis nicht gefunden: $BACKEND" >&2
  exit 2
fi

START=$(date +%s)
(
  cd "$BACKEND" && \
  ./mvnw -q test -Dtest='PackageCycleRatchetTest,LayerRuleTest,SourceSizeRatchetTest' \
    -Dsurefire.failIfNoSpecifiedTests=false
)
RC=$?
END=$(date +%s)
DURATION=$((END - START))

if [ "$RC" -eq 0 ]; then
  echo "arch-guard OK -- PackageCycleRatchetTest, LayerRuleTest, SourceSizeRatchetTest gruen (${DURATION}s)"
else
  echo "arch-guard FAIL -- exit ${RC} nach ${DURATION}s"
fi
exit "$RC"
