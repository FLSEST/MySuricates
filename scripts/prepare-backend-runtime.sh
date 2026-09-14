#!/usr/bin/env bash
# Build in a private snapshot and print the immutable JAR path on stdout.
set -euo pipefail
REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_ROOT="${XDG_STATE_HOME:-$HOME/.local/state}/autotrigger-runtime"
RUN_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
mkdir -p "$RUNTIME_ROOT"
MANIFEST="$(python3 "$REPO_ROOT/ai-automation-backend/src/main/resources/scripts/run-workspace.py" \
  --source "$REPO_ROOT" --root "$RUNTIME_ROOT/snapshots" --project runtime \
  --run "$RUN_ID" --mode working-tree)"
RUNTIME_SOURCE="$(python3 -c 'import sys,json; print(json.load(sys.stdin)["executionPath"])' <<< "$MANIFEST")"
BUILD_LOG="$RUNTIME_ROOT/snapshots/runtime/$RUN_ID/logs/package.log"
if ! (cd "$RUNTIME_SOURCE/ai-automation-backend" && mvn -q -DskipTests package > "$BUILD_LOG" 2>&1); then
  echo "Runtime-Build fehlgeschlagen; Log: $BUILD_LOG" >&2
  tail -40 "$BUILD_LOG" >&2
  exit 1
fi
python3 - "$RUNTIME_SOURCE" "$RUNTIME_ROOT" <<'PY'
import hashlib
from pathlib import Path
import sys
source, root = map(Path, sys.argv[1:])
jars = list((source / 'ai-automation-backend/target').glob('ai-automation-backend-*.jar'))
if len(jars) != 1:
    raise SystemExit('Expected exactly one packaged backend JAR')
data = jars[0].read_bytes()
digest = hashlib.sha256(data).hexdigest()
folder = root / 'artifacts' / digest
folder.mkdir(parents=True, exist_ok=True)
target = folder / 'backend.jar'
try:
    with target.open('xb') as output:
        output.write(data)
    target.chmod(0o400)
except FileExistsError:
    if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
        raise SystemExit('Existing runtime artifact has changed; refusing to replace it')
print(target)
PY
