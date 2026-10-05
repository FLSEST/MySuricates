#!/usr/bin/env bash
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; source "$SCRIPT_DIR/common.sh"; cd_root
section "Documentation readiness check"
# Die Liste nennt die oeffentlichen Dokumente, die dieses Repo tatsaechlich fuehrt.
# Vorher standen hier fuenf englische Dateinamen (docs/architecture.md,
# docs/agentic-development.md, docs/public-readiness.md, docs/security.md,
# docs/use-cases.md), die es nie gab: die Doku heisst docs/architektur.md und liegt
# zweisprachig unter docs/ und docs/en/. Das Gate stand dadurch dauerhaft auf YELLOW.
for f in README.md README.de.md LICENSE NOTICE SECURITY.md CONTRIBUTING.md \
         docs/GETTING-STARTED.md docs/architektur.md docs/baukasten.md \
         docs/workflow-pool-kuratierung.md; do
  [ -e "$f" ] && echo "present: $f" || echo "missing: $f"
done
section "Existing docs inventory"
find docs -maxdepth 2 -type f 2>/dev/null | sort | head -220 | redact || true
section "Public narrative keywords in docs (counts)"
for pat in 'agentic|agentisch' 'human[- ]in[- ]the[- ]loop|human review|mensch' 'security|secret|redact' 'test|quality|lint|build' 'living lab|exploration|research'; do
  count=$(pgrep_count_total -i "$pat" README.md docs AGENTS.md CLAUDE.md)
  printf '%s: %s\n' "$pat" "$count"
done
# Einstieg: Wie viel liest ein Besucher, bevor er erfaehrt, wie man es ausprobiert?
# Gezaehlt werden die Woerter vor der ersten Ueberschrift, die nach Ausprobieren
# klingt; HTML-Kommentare zaehlen nicht. Ohne solche Ueberschrift ist das ein Befund
# fuer sich. Kein todo_item: der Befund laeuft ueber das Audit (TODO 1b84ecab).
QUICKSTART_MAX_WORDS="${PUBLIC_READINESS_QUICKSTART_MAX_WORDS:-500}"
QUICKSTART_HEADING='^#{1,3}[[:space:]]+.*(quickstart|quick start|schnellstart|ausprobieren|try it|getting started|installation)'
section "README entry (words before the quickstart heading, limit ${QUICKSTART_MAX_WORDS})"
entry_findings=0
for f in README.md README.de.md; do
  [ -f "$f" ] || continue
  heading_line=$(grep -niE "$QUICKSTART_HEADING" "$f" | head -1 | cut -d: -f1)
  if [ -z "$heading_line" ]; then
    echo "BEFUND: readme-ohne-schnellstart $f — keine Ueberschrift zum Ausprobieren gefunden"
    entry_findings=$((entry_findings + 1))
    continue
  fi
  words=$(head -n $((heading_line - 1)) "$f" | python3 -c 'import re,sys; print(re.sub(r"<!--.*?(-->|\Z)", "", sys.stdin.read(), flags=re.S))' | wc -w | tr -d ' ')
  if [ "$words" -gt "$QUICKSTART_MAX_WORDS" ]; then
    echo "BEFUND: schnellstart-spaet $f:$heading_line — ${words} Woerter davor (Grenze ${QUICKSTART_MAX_WORDS})"
    entry_findings=$((entry_findings + 1))
  else
    echo "ok: $f:$heading_line — ${words} Woerter davor"
  fi
done

# Agenten-Anweisungen, die nur der Maintainer befolgen kann: Verweise auf seine
# Shell-Session und seine Claude-Code-Sitzungen. Eine Beispiel-Nutzlast mit
# "sourceAgent" in der API-Doku zaehlt bewusst nicht.
section "Agent instructions written for the maintainer only"
maintainer_findings=0
for f in CLAUDE.md AGENTS.md; do
  [ -f "$f" ] || continue
  for marker in 'SHELL_SESSION_ID' 'claude --resume'; do
    n=$(grep -cF -- "$marker" "$f")
    if [ "$n" -gt 0 ]; then
      echo "BEFUND: maintainer-anweisung $f — '${marker}' ${n}x"
      maintainer_findings=$((maintainer_findings + 1))
    fi
  done
done
[ "$maintainer_findings" -eq 0 ] && echo "ok: keine Maintainer-Marker in CLAUDE.md/AGENTS.md"

section "Gate status"
missing=0
for f in LICENSE SECURITY.md CONTRIBUTING.md docs/GETTING-STARTED.md docs/baukasten.md; do [ -e "$f" ] || missing=$((missing+1)); done
if [ "$missing" -eq 0 ] && [ $((entry_findings + maintainer_findings)) -gt 0 ]; then
  echo "gate_status: YELLOW"
  echo "gate_category: documentation-gaps"
  echo "gate_description: Einstieg oder Agenten-Anweisungen nicht oeffentlich tauglich (${entry_findings} Einstieg, ${maintainer_findings} Maintainer-Marker)."
elif [ "$missing" -gt 0 ]; then
  echo "gate_status: YELLOW"
  echo "gate_category: documentation-gaps"
  echo "gate_description: Public-facing documentation gaps exist; create reviewed docs before launch."
  echo "todo_item: Fehlende oeffentliche Dokumente ergaenzen | MEDIUM | documentation,release | Eines der Pflichtdokumente fehlt: LICENSE, SECURITY.md, CONTRIBUTING.md, docs/GETTING-STARTED.md oder docs/baukasten.md."
else
  echo "gate_status: GREEN"
  echo "gate_category: documentation-gaps"
  echo "gate_description: Core public-facing documentation markers exist."
fi
