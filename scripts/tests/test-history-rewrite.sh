#!/usr/bin/env bash
# scripts/history-rewrite/rewrite.sh on a throwaway repo (TODO 0bf5eed4): everything the rewrite has to
# remove is planted once - a path with personal data, a private address, a private term in a message,
# a Claude-Session trailer, a side branch, a non-release tag - and the result plus the verification are
# checked, including that the verification reports a leftover.
set -uo pipefail
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REWRITE="$HERE/../history-rewrite/rewrite.sh"
MESSAGES="$HERE/../history-rewrite/messages.py"
PASS=0; FAIL=0
check() { if eval "$2"; then echo "  ok   $1"; PASS=$((PASS + 1)); else echo "  FAIL $1"; FAIL=$((FAIL + 1)); fi; }

if ! command -v git-filter-repo >/dev/null 2>&1; then
  echo "skip: git-filter-repo not installed"; echo "passed=0 failed=0"; exit 0
fi
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
SRC="$TMP/src"; S="$TMP/state"; mkdir -p "$SRC" "$S"
g() { git -C "$SRC" "$@"; }

echo "1) source repo with everything that has to go"
g init -q -b main
g config user.name "Dev"; g config user.email "dev@private.example"
mkdir -p "$SRC/secret" "$SRC/ai-automation-frontend/src/i18n"
printf "export const de = {\n};\n" > "$SRC/ai-automation-frontend/src/i18n/de.ts"
printf "export const en = {\n};\n" > "$SRC/ai-automation-frontend/src/i18n/en.ts"
echo "Max Muster, Tel 000" > "$SRC/secret/personal.txt"; echo app > "$SRC/app.txt"
g add -A; g commit -q -m "feat: Bewerbung für geheimname angelegt, weil es noch fehlt (deadbee1 Erster Schritt)"
g rm -q secret/personal.txt; echo app2 > "$SRC/app.txt"; g add -A
g commit -q -m "$(printf 'fix: app aktualisiert und Datei entfernt\n\nClaude-Session: https://example.invalid/s\nCo-Authored-By: Bot <noreply@example.invalid>')"
g commit -q --allow-empty -m "docs: english only"
g tag v0.1.0; g tag stable-123
g branch side
check "source has the planted path" '[ "$(g log --all --format=%H -- secret/personal.txt | wc -l)" -ge 1 ]'

echo "2) state: private lists and a complete map"
echo "secret/personal.txt" > "$S/remove-paths.txt"
echo "Dev <dev@users.noreply.github.com> <dev@private.example>" > "$S/mailmap.txt"
echo "geheimname==>[name]" > "$S/message-replacements.txt"
python3 -I "$MESSAGES" extract --repo "$SRC" --state "$S" --run r1 --rev main >/dev/null
ANSWER="$(python3 -I - "$S/runs/r1/batches/001.json" <<'PY'
import json, sys
batch = json.load(open(sys.argv[1]))
out = []
for m in batch['messages']:
    if m['message'].startswith('feat:'):
        text = 'feat: application for geheimname created because it was missing\n\nTodo: deadbee1 (Erster Schritt)\n'
    else:
        text = 'fix: update app and remove file\n\nCo-Authored-By: Bot <noreply@example.invalid>\n'
    out.append({'hash': m['hash'], 'message': text})
print(json.dumps({'messages': out}))
PY
)"
python3 -I "$MESSAGES" check --state "$S" --run r1 --batch 001 <<<"$ANSWER" >/dev/null
check "every commit has a map entry" '[ "$(python3 -I "$MESSAGES" map-missing --repo "$SRC" --map "$S/map.jsonl")" = 0 ]'

echo "3) run"
OUT="$(bash "$REWRITE" run --state "$S" --source "$SRC" --tag v0.2.0 --create-tag 2>&1)"; RC=$?
W="$S/work"
check "run ends without a finding (rc=$RC)" '[ "$RC" = 0 ]'
check "planted path is gone from every commit" '[ -z "$(git -C "$W" log --all --format=%H -- secret/personal.txt)" ]'
check "only the noreply address is left" '[ "$(git -C "$W" log --all --format="%ae%n%ce" | sort -u | tr "\n" " ")" = "dev@users.noreply.github.com " ]'
MSGS="$(git -C "$W" log --all --format=%B)"
check "messages come from the map (English)" 'grep -F "update app and remove file" <<<"$MSGS" >/dev/null'
check "TODO bracket became a trailer" 'grep -x "Todo: deadbee1 (Erster Schritt)" <<<"$MSGS" >/dev/null'
check "private term replaced after the map" '! grep -F geheimname <<<"$MSGS" >/dev/null && grep -F "[name]" <<<"$MSGS" >/dev/null'
check "no Claude-Session trailer" '! grep "^Claude-Session:" <<<"$MSGS" >/dev/null'
check "only main, side branch not cloned" '[ "$(git -C "$W" for-each-ref --format="%(refname)" refs/heads)" = "refs/heads/main" ]'
check "only v* tags, release tag on the tip" '[ "$(git -C "$W" tag -l | tr "\n" " ")" = "v0.1.0 v0.2.0 " ] && [ "$(git -C "$W" rev-parse "v0.2.0^{commit}")" = "$(git -C "$W" rev-parse main)" ]'
check "dates kept: author date of the first commit unchanged" '[ "$(git -C "$W" log --reverse --format=%at main | sed -n 1p)" = "$(g log --reverse --format=%at main | sed -n 1p)" ]'
check "tip tree equals the source" '[ "$(git -C "$W" rev-parse "main^{tree}")" = "$(g rev-parse "main^{tree}")" ]'
check "work clone has no remote" '[ -z "$(git -C "$W" remote)" ]'
check "source is untouched (still has the path)" '[ "$(g log --all --format=%H -- secret/personal.txt | wc -l)" -ge 1 ]'

echo "4) refusals and the verification"
OUT="$(bash "$REWRITE" run --state "$S" --source "$SRC" 2>&1)"; RC=$?
check "an existing work clone is refused" '[ "$RC" = 1 ] && grep -F "existiert schon" <<<"$OUT" >/dev/null'
S2="$TMP/state2"; mkdir -p "$S2"; cp "$S/remove-paths.txt" "$S/mailmap.txt" "$S2/"
echo '{"hash": "0000000", "message": "x\n"}' > "$S2/map.jsonl"
OUT="$(bash "$REWRITE" run --state "$S2" --source "$SRC" 2>&1)"; RC=$?
check "commits without a map entry are refused" '[ "$RC" = 1 ] && grep -F "ohne Eintrag in map.jsonl" <<<"$OUT" >/dev/null'
echo "app.txt" >> "$S/remove-paths.txt"   # a path that is still in the tip -> must be reported
OUT="$(bash "$REWRITE" verify --state "$S" --source "$SRC" 2>&1)"; RC=$?
check "verify reports a path that is still present" '[ "$RC" = 1 ] && grep -F "Pfad noch in" <<<"$OUT" >/dev/null'
echo "regex:application for==>X" >> "$S/message-replacements.txt"
OUT="$(bash "$REWRITE" verify --state "$S" --source "$SRC" 2>&1)"; RC=$?
check "verify reports a replacement pattern still in a message" 'grep -F "Ersetzungsmuster kommen noch" <<<"$OUT" >/dev/null'

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
