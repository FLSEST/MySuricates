#!/usr/bin/env bash
# arch-guard.sh -- fuehrt die drei Architektur-Ratchet-Tests aus, die eine gezielte
# -Dtest-Auswahl sonst leicht vergisst (TODO 46eb35aa): PackageCycleRatchetTest,
# LayerRuleTest und SourceSizeRatchetTest messen ueber jdeps bzw. Dateigroessen die
# kompilierten Klassen -- sie sind genau dann nuetzlich, wenn sie frueh laufen, nicht
# erst beim vollen Integrations-Checkpoint.
#
# Wer eine neue Klasse anlegt, eine Klasse zwischen Paketen verschiebt oder ein Paket
# umbenennt, fuehrt dieses Skript zusaetzlich zur eigenen gezielten Testauswahl aus
# (siehe docs/entwicklung.md -> Abschnitt zu gezielten Testlaeufen).
#
# Zeitbudget: die drei Klassen selbst brauchen wenige Sekunden; hinzu kommt der
# Kompilat-Schritt (schneller Incremental-Build bei vorhandenem target/, sonst der
# volle Compile). Das Skript weist die tatsaechliche Laufzeit am Ende aus.
#
# Run: bash scripts/tests/arch-guard.sh   (exit 0 = alle drei Tests gruen)
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
