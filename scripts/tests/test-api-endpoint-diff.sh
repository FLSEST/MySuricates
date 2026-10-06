#!/usr/bin/env bash
# Tests for scripts/public-readiness/api-endpoint-diff.sh (TODO 2cbc00a7).
# Builds a fixture controller directory + fixture docs in a temp directory and
# checks that the diff script reports missing/outdated endpoints deterministically.
# Run: bash scripts/tests/test-api-endpoint-diff.sh   (exit 0 = all checks ok)
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

# Fixture controller: class base path + bare @GetMapping + path variable + 2 methods on
# the same sub-path + one endpoint that is deliberately in NO doc.
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

# Second controller without class base path (full path directly on the method), like WebhookController.
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

# DE doc: covers list/create/get/delete, NOT "activate" (-> FEHLT_IN_DOC).
# Additionally an endpoint that does not exist in the code (-> NUR_IN_DOC).
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

# EN doc: additionally covers "activate" (only DE lacks it), so the language difference is testable.
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
# Replace {id} with another variable name -> must still count as matching.
sed -i 's/{id}/{widgetId}/g' "$DOC_DE2"
OUT2=$(bash "$SCRIPT" "$CTRL" "$DOC_DE2" "$DOC_EN" 2>&1)
check "{widgetId} im Doc matcht {id} im Code (kein FEHLT_IN_DOC fuer /{id})" \
  '! grep -qE "FEHLT_IN_DOC de (GET|DELETE) /api/v1/widgets/\{id\}$" <<<"$OUT2"'

echo "3) GREEN-Fixture: vollstaendige, deckungsgleiche Docs -> Exit 0, keine Ausgabe"
# DOC_EN already covers all 5 code endpoints 1:1 (no archive extra) -> use as DE and EN doc.
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

echo "6) Regressions-Fixture: produces/consumes duerfen keinen Zusatz-Pfad erzeugen; @RequestMapping(method=...) wird erkannt"
CTRL2="$TMP/controller2"
mkdir -p "$CTRL2"

# IMPORTANT fix (review 2cbc00a7): @GetMapping(value=..., produces="text/markdown") and
# @PostMapping(value=..., consumes=..., produces=...) must ONLY yield the value/path path,
# not every quoted string in the annotation (the original bug read
# "text/markdown"/"application/xml"/"application/json" wrongly as further endpoints).
# MINOR fix: bare @RequestMapping(method = RequestMethod.X, path = "...") instead of @XMapping
# must go through the same branch in extract_controllers() as @PutMapping("...").
cat > "$CTRL2/MiscMappingController.java" <<'JAVA'
package com.automation.controller;

import org.springframework.web.bind.annotation.*;

@RequestMapping("/api/v1/misc")
@RestController
public class MiscMappingController {

    @GetMapping(value = "/{id}/snippet", produces = "text/markdown")
    public String snippet() { return ""; }

    @PostMapping(value = "/{id}/import", consumes = "application/xml", produces = "application/json")
    public String importData() { return ""; }

    @RequestMapping(method = RequestMethod.PUT, path = "/{id}/status")
    public String setStatus() { return ""; }
}
JAVA

DOC_MISC="$TMP/api-referenz.misc.md"
cat > "$DOC_MISC" <<'MD'
## Misc

### `GET /api/v1/misc/{id}/snippet`

Snippet abrufen.

### `POST /api/v1/misc/{id}/import`

Daten importieren.

### `PUT /api/v1/misc/{id}/status`

Status setzen.
MD

OUT6=$(bash "$SCRIPT" "$CTRL2" "$DOC_MISC" "$DOC_MISC" 2>&1); RC6=$?
check "Exit 0: produces/consumes + @RequestMapping(method=...) ergeben exakte Deckung" '[ "$RC6" -eq 0 ]'
check "keine Ausgabe (kein Bogus-Endpunkt, kein NUR_IN_DOC fuer /status)" '[ -z "$OUT6" ]'
check "kein 'text/markdown' als eigener Pfad extrahiert"   '! grep -qF "text/markdown" <<<"$OUT6"'
check "kein 'application/xml' als eigener Pfad extrahiert" '! grep -qF "application/xml" <<<"$OUT6"'
check "kein 'application/json' als eigener Pfad extrahiert" '! grep -qF "application/json" <<<"$OUT6"'
check "@RequestMapping(method=PUT, path=...) korrekt erkannt (keine NUR_IN_DOC-Zeile fuer /status)" \
  '! grep -qF "misc/{id}/status" <<<"$OUT6"'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" -eq 0 ]
