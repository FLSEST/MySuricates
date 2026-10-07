#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root

section "Test map"

inventory_section "Test files (sample)"
find . -type f \( -name '*Test.java' -o -name '*Tests.java' -o -name '*.test.ts' -o -name '*.test.tsx' -o -name '*.spec.ts' -o -name '*.spec.tsx' -o -name '*.cy.ts' \) ! -path '*/node_modules/*' ! -path '*/target/*' ! -path '*/dist/*' ! -path '*/build/*' ! -path '*/data/*' | sed 's#^./##' | sort | head -120 | redact

inventory_section "Production source counts"
java_src=$(find ai-automation-backend/src/main -type f -name '*.java' 2>/dev/null | wc -l | tr -d ' ')
ts_src=$(find ai-automation-frontend/src -type f \( -name '*.ts' -o -name '*.tsx' \) ! -name '*.test.*' ! -name '*.spec.*' 2>/dev/null | wc -l | tr -d ' ')
echo "java_source_files: $java_src"
echo "frontend_source_files: $ts_src"

inventory_section "Test source counts"
java_test=$(find ai-automation-backend/src/test -type f -name '*.java' 2>/dev/null | wc -l | tr -d ' ')
ts_test=$(find ai-automation-frontend/src ai-automation-frontend/tests -type f \( -name '*.test.*' -o -name '*.spec.*' \) 2>/dev/null | wc -l | tr -d ' ')
echo "java_test_files: $java_test"
echo "frontend_test_files: $ts_test"

inventory_section "Test/Source ratio per module"
ratio() {
  local tests="$1" src="$2"
  if [ "$src" -eq 0 ]; then echo "n/a"; return; fi
  awk -v t="$tests" -v s="$src" 'BEGIN { printf "%.3f\n", t/s }'
}
backend_ratio=$(ratio "$java_test" "$java_src")
frontend_ratio=$(ratio "$ts_test" "$ts_src")
echo "backend_test_ratio: $backend_ratio"
echo "frontend_test_ratio: $frontend_ratio"

# Threshold heuristic: GREEN >0.4, YELLOW 0.2..0.4, RED <0.2.
classify() {
  local r="$1"
  if [ "$r" = "n/a" ]; then echo "UNKNOWN"; return; fi
  awk -v r="$r" 'BEGIN { if (r+0 > 0.4) print "GREEN"; else if (r+0 >= 0.2) print "YELLOW"; else print "RED" }'
}
backend_class=$(classify "$backend_ratio")
frontend_class=$(classify "$frontend_ratio")
echo "backend_test_class: $backend_class"
echo "frontend_test_class: $frontend_class"

# Aggregate: worst of the two wins. Unknown counts as YELLOW.
worst() {
  local a="$1" b="$2"
  for c in RED YELLOW UNKNOWN GREEN; do
    if [ "$a" = "$c" ] || [ "$b" = "$c" ]; then echo "$c"; return; fi
  done
  echo "GREEN"
}
overall=$(worst "$backend_class" "$frontend_class")
[ "$overall" = "UNKNOWN" ] && overall=YELLOW

section "Gate status"
case "$overall" in
  GREEN)
    echo "gate_status: GREEN"
    echo "gate_category: test-coverage-shape"
    echo "gate_description: Test/source ratio above 0.4 in both modules. Coverage depth still requires manual review."
    ;;
  YELLOW)
    echo "gate_status: YELLOW"
    echo "gate_category: test-coverage-shape"
    echo "gate_description: Test/source ratio between 0.2 and 0.4 in at least one module. Identify gaps for critical paths."
    echo "todo_item: Close test-coverage gaps in module below 0.4 ratio | MEDIUM | testing,public-readiness | Add tests where ratio is YELLOW (workflow execution, redaction, gate parsing)."
    ;;
  RED)
    echo "gate_status: RED"
    echo "gate_category: test-coverage-shape"
    echo "gate_description: Test/source ratio below 0.2 in at least one module. Public claims about reliability are not defensible without raising coverage."
    echo "todo_item: Raise test coverage above 0.2 ratio before public claims | HIGH | testing,public-readiness | Module(s) below 0.2 require tests for core paths before any reliability claim is published."
    ;;
esac
