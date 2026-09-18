#!/usr/bin/env bash
# Tests fuer scripts/public-readiness/api-endpoint-diff.sh (TODO 2cbc00a7).
# Baut ein Fixture-Controller-Verzeichnis + Fixture-Docs in einem Temp-Verzeichnis und
# prueft, dass das Diff-Skript fehlende/veraltete Endpunkte deterministisch meldet.
# Run: bash scripts/tests/test-api-endpoint-diff.sh   (exit 0 = alle Checks ok)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SCRIPT="$REPO/scripts/public-readiness/api-endpoint-diff.sh"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

check() {  # $1 = label, $2 = shell condition
  if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi
}

echo "0) Skript existiert und ist ausfuehrbar (RED, falls nicht)"
check "api-endpoint-diff.sh vorhanden" "[ -f '$SCRIPT' ]"
if [ ! -f "$SCRIPT" ]; then
  echo "passed=$PASS failed=$FAIL"
  exit 1
fi

CTRL="$TMP/controller"
mkdir -p "$CTRL"

# Fixture-Controller: Klassen-Basis-Pfad + bare @GetMapping + Pfadvariable + 2 Methoden auf
# demselben Sub-Pfad + ein Endpunkt, der absichtlich in KEINEM Doc steht.
cat > "$CTRL/WidgetController.java" <<'JAVA'
package com.automation.controller;

import org.springframework.web.bind.annotation.*;

@RequestMapping("/api/v1/widgets")
@RestController
public class WidgetController {

    @GetMapping
    public String list() { return ""; }

    @PostMapping
    public String create() { return ""; }

    @GetMapping("/{id}")
    public String get() { return ""; }

    @DeleteMapping("/{id}")
    public String delete() { return ""; }

    @PostMapping("/{id}/activate")
    public String activate() { return ""; }
}
JAVA

# Zweiter Controller ohne Klassen-Basis-Pfad (voller Pfad direkt an der Methode), wie WebhookController.
cat > "$CTRL/GadgetController.java" <<'JAVA'
package com.automation.controller;

import org.springframework.web.bind.annotation.*;

@RestController
public class GadgetController {

    @GetMapping("/api/v1/gadgets")
    public String list() { return ""; }
}
JAVA

DOC_DE="$TMP/api-referenz.de.md"
DOC_EN="$TMP/api-referenz.en.md"

# DE-Doc: deckt list/create/get/delete ab, NICHT "activate" (-> FEHLT_IN_DOC).
# Zusaetzlich ein Endpunkt, den es im Code nicht gibt (-> NUR_IN_DOC).
cat > "$DOC_DE" <<'MD'
## Widgets

### `GET /api/v1/widgets`

Liste.

### `POST /api/v1/widgets`

Anlegen.

### `GET /api/v1/widgets/{id}`

Einzelnes Widget.

### `DELETE /api/v1/widgets/{id}`

Loeschen.

### `GET /api/v1/gadgets`

Liste.

### `POST /api/v1/widgets/{id}/archive`

Diesen Endpunkt gibt es im Code nicht (mehr).
MD

# EN-Doc: deckt zusaetzlich "activate" ab (nur DE fehlt es), damit der Sprachunterschied testbar ist.
cat > "$DOC_EN" <<'MD'
## Widgets

### `GET /api/v1/widgets`

List.

### `POST /api/v1/widgets`

Create.

### `GET /api/v1/widgets/{id}`

Single widget.

### `DELETE /api/v1/widgets/{id}`

Delete.

### `POST /api/v1/widgets/{id}/activate`

Activate.

### `GET /api/v1/gadgets`

List.
MD

echo "1) RED-Fixture: Skript findet Luecken und meldet Exit 1"
OUT=$(bash "$SCRIPT" "$CTRL" "$DOC_DE" "$DOC_EN" 2>&1); RC=$?
check "Exit-Code 1 bei Luecken"                 '[ "$RC" -eq 1 ]'
check "FEHLT_IN_DOC de POST /api/v1/widgets/{id}/activate" \
  'grep -qF "FEHLT_IN_DOC de POST /api/v1/widgets/{id}/activate" <<<"$OUT"'
check "kein FEHLT_IN_DOC en fuer activate (EN deckt es ab)" \
  '! grep -qF "FEHLT_IN_DOC en POST /api/v1/widgets/{id}/activate" <<<"$OUT"'
check "NUR_IN_DOC de POST /api/v1/widgets/{id}/archive" \
  'grep -qF "NUR_IN_DOC de POST /api/v1/widgets/{id}/archive" <<<"$OUT"'
check "kein Treffer fuer GET /api/v1/gadgets (in beiden Docs + Code vorhanden)" \
  '! grep -qF "/api/v1/gadgets" <<<"$OUT"'

echo "2) Normalisierung: Pfadvariablen-Namen werden ignoriert ({id} vs. {widgetId})"
DOC_DE2="$TMP/api-referenz.de2.md"
cp "$DOC_DE" "$DOC_DE2"
# Ersetze {id} durch einen anderen Variablennamen -> muss weiterhin als deckungsgleich gelten.
sed -i 's/{id}/{widgetId}/g' "$DOC_DE2"
OUT2=$(bash "$SCRIPT" "$CTRL" "$DOC_DE2" "$DOC_EN" 2>&1)
check "{widgetId} im Doc matcht {id} im Code (kein FEHLT_IN_DOC fuer /{id})" \
  '! grep -qE "FEHLT_IN_DOC de (GET|DELETE) /api/v1/widgets/\{id\}$" <<<"$OUT2"'

echo "3) GREEN-Fixture: vollstaendige, deckungsgleiche Docs -> Exit 0, keine Ausgabe"
# DOC_EN deckt bereits alle 5 Code-Endpunkte 1:1 ab (kein archive-Extra) -> als DE- und EN-Doc verwenden.
OUT3=$(bash "$SCRIPT" "$CTRL" "$DOC_EN" "$DOC_EN" 2>&1); RC3=$?
check "Exit-Code 0 wenn beide Docs vollstaendig sind" '[ "$RC3" -eq 0 ]'
check "keine Ausgabe im GREEN-Fall"                   '[ -z "$OUT3" ]'

echo "4) Fehlendes Controller-Verzeichnis ist ein harter Fehler (exit != 0/1, kein stilles OK)"
OUT4=$(bash "$SCRIPT" "$TMP/does-not-exist" "$DOC_DE" "$DOC_EN" 2>&1); RC4=$?
check "Exit-Code 2 bei fehlendem Verzeichnis" '[ "$RC4" -eq 2 ]'

echo "5) Realer Repo-Lauf gegen die echten Controller + docs/api-referenz.md (DE/EN)"
OUT5=$(bash "$SCRIPT" 2>&1); RC5=$?
echo "$OUT5"
check "Diff-Skript laeuft gegen das echte Repo durch (exit 0 oder 1)" '[ "$RC5" -eq 0 ] || [ "$RC5" -eq 1 ]'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
