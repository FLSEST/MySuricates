# Ghost-Run Root-Cause Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Zwei unabhängige Defekte beheben, die am 2026-07-27 elf Workflow-Runs als „Ghost run" haben sterben lassen — der Quality-Gate-Workflow baut im laufenden Dev-Checkout, und der `WorkflowExecutor` lässt `Error`s stumm durchrutschen.

**Architecture:** Zwei getrennte Ebenen. (A) *Ursache*: der Quality-Gate-Workflow führt `mvn test` künftig in einem dedizierten, wiederverwendeten git-Worktree außerhalb des Live-Checkouts aus — bereitgestellt von einem neuen, idempotenten Shell-Skript. (B) *Schadensbegrenzung*: `WorkflowExecutor.executeBlocks` fängt `Throwable` statt nur `Exception`, damit ein `Error` den Run sofort mit echter Fehlermeldung auf `FAILED` setzt statt ihn 30 Minuten später als anonymen Ghost enden zu lassen.

**Tech Stack:** Java 21, Spring Boot 3.4.13, JUnit 5 + Mockito + AssertJ, Bash, git worktree, MySuricates REST API (`/api/v1`).

---

## Incident-Beleg (2026-07-27)

Die Kette, verifiziert aus Log, Dateisystem und DB:

| Zeit (lokal) | Ereignis | Beleg |
|---|---|---|
| 08:50:07 | Dev-Backend startet via `mvn spring-boot:run`, Classpath `target/classes` **als Verzeichnis** → Klassen werden über die gesamte JVM-Lebensdauer lazy von Platte geladen | `ps` Startzeit, `-cp …/target/classes` |
| 08:50:18–08:50:27 | Scheduler feuert 14 fällige CRON-Schedules in 9 s (Kaltstart-Backlog) | 16 Runs mit `startedAt` in diesem Fenster |
| 08:50:24 | Darunter *AutoPilot Backend Quality Gate*, Block 0: `cd ai-automation-backend && mvn test -q` | Workflow `caf831c2`, Step-Input |
| 08:50:35.86–08:50:42.27 | Maven schreibt **799 `.class`-Dateien** neu — mtime *nach* JVM-Start | `find target/classes -newermt "2026-07-27 08:50:30" \| wc -l` → 799 |
| 08:50:25 | Der surefire-Fork stirbt am selben Chaos | `target/surefire-reports/2026-07-27T08-50-25_139-jvmRun1.dump`, „TestEngine with ID 'junit-jupiter' failed to discover tests" |
| ab 08:50:25 | Laufende JVM: 14× `java.lang.NoClassDefFoundError`, u. a. `com/automation/service/todo/GateStatusResolver$1` | `mysuricates-error.log` |
| 08:50:30 | Erster Run endet ohne Status | `AuditIngestionService - Stored invalid audit report for run 012215d3…: Run ended with status RUNNING` |
| 09:21:18 / 09:31:18 | `GhostRunWatchdogService` räumt 9 + 2 Runs ab | „Ghost run detected by watchdog: no active execution thread found" |

**Warum daraus ein Ghost wird:** `WorkflowExecutor.executeBlocks` hat nur `catch (InterruptedException)` und `catch (Exception)` (`WorkflowExecutor.java:543-555`). `NoClassDefFoundError` ist ein `Error` und rutscht an beiden vorbei. Das `finally` (`:556`) läuft trotzdem: es gibt das Semaphore frei, macht `executionRegistry.unregister(runId)` und speichert den Run — aber `run.setStatus(…)` steht ausschließlich *im* try/catch. Ergebnis: Thread tot, aus `activeExecutions` raus, DB-Status weiter `RUNNING`, kein Fehler hinterlegt.

`NoClassDefFoundError` ist zudem **sticky** — der Classloader cached das Load-Failure dauerhaft. Deshalb sind auch die beiden 09:00-Runs identisch gestorben, obwohl die `.class`-Dateien längst wieder auf Platte lagen. Nur ein Backend-Neustart heilt das.

**Vorgeschichte:** TODO `9917715e` beschreibt exakt diesen Defekt seit 2026-06-09 („NoClassDefFoundError: ContainerMapper — mvn clean loescht generierte MapStruct-Impl. Fix: Tests in isoliertem git-Worktree ausfuehren"). Heute ist ein Rückfall nach sieben Wochen.

---

## Global Constraints

- **Kein `mvn`, `mvn clean` oder Build im Haupt-Checkout, solange das Dev-Backend läuft.** Das ist der gesamte Punkt dieses Plans.
- **Backend nicht eigenständig neu starten.** Nur auf explizite Aufforderung; vorher laufende Runs prüfen (`GET /api/v1/executions?status=RUNNING`).
- **Keine neuen Dependencies.** Weder Maven noch npm — die Fixes kommen mit Bordmitteln aus (`git worktree`, `sha256sum`, JUnit/Mockito/AssertJ sind bereits da).
- **Tests müssen vor jedem Commit grün sein** (`mvn test` — ab Task 2 im Worktree, davor gezielt via `-Dtest=…`).
- **SQLite pool-size = 1**: keine langsamen Operationen in `@Transactional`. Beide Fixes fassen keine Transaktionsgrenzen an.
- **Keine Flyway-Migration nötig** — kein Schema-Change.
- Sprache: Code-Kommentare und Log-Messages Englisch (Bestandskonvention in `WorkflowExecutor`), TODO-Titel Deutsch.

---

## File Structure

| Datei | Verantwortung | Aktion |
|---|---|---|
| `ai-automation-backend/src/main/java/com/automation/service/workflow/WorkflowExecutor.java` | Kern-Ausführungsschleife; hier das `Throwable`-Handling | Modify `:547-555` |
| `ai-automation-backend/src/test/java/com/automation/service/workflow/WorkflowExecutorTest.java` | Unit-Tests der Ausführungsschleife (Mockito, Executor läuft inline) | Modify — ein Test dazu |
| `scripts/qa-worktree.sh` | Idempotente Bereitstellung des isolierten Build-Worktrees | **Create** |
| Workflow `caf831c2-cd95-447f-b349-a695a8f21063` (*AutoPilot Backend Quality Gate*) | Führt die Gate-Kommandos aus | Modify via `PUT /api/v1/workflows/{id}` |
| `docs/entwicklung.md` | Build-/Test-Anleitung | Modify — Abschnitt zum QA-Worktree |

**Reihenfolge:** Task 1 zuerst. Er ist unabhängig vom Worktree-Fix und verwandelt jeden künftigen `Error` sofort in einen sichtbaren Fehlschlag — das macht die Verifikation von Task 2–4 überhaupt erst aussagekräftig.

---

## Verworfene Alternative (bewusst nicht im Plan)

**Zusätzlicher Invarianten-Guard im `finally`** („wenn Status noch `RUNNING`, dann `FAILED` setzen"). Klingt nach sinnvollem zweiten Netz, ist es hier aber nicht: nach Task 1 gibt es keinen erreichbaren Pfad mehr, der das `finally` mit `run != null` **und** Status `RUNNING` erreicht — der einzige `return` im try-Body (`:546`) liegt im `InterruptedException`-Zweig, wo `run` noch `null` ist. Der Guard wäre also nicht testbarer toter Code. Wer ihn später doch will, braucht zuerst einen erreichbaren Trigger.

---

### Task 1: `WorkflowExecutor` fängt `Throwable` statt `Exception`

**Files:**
- Modify: `ai-automation-backend/src/main/java/com/automation/service/workflow/WorkflowExecutor.java:547-555`
- Test: `ai-automation-backend/src/test/java/com/automation/service/workflow/WorkflowExecutorTest.java`

**Interfaces:**
- Consumes: nichts aus früheren Tasks.
- Produces: keine neuen Signaturen. Verhaltensgarantie für alle Folgetasks: *ein Run, dessen Executor-Thread an einem `Throwable` stirbt, wird sofort mit `RunStatus.FAILED` und befüllter `error`-Map gespeichert — nie mit `RUNNING`.*

**Hintergrund für die Umsetzung:** `WorkflowExecutorTest` führt den an `virtualThreadExecutor.submit(Runnable)` übergebenen Task **synchron auf dem Test-Thread** aus (`WorkflowExecutorTest.java:106-109`). Ein ungefangener `Error` fliegt dadurch bis in die Testmethode — der Test schlägt vor dem Fix mit genau diesem `Error` fehl, nicht mit einer Assertion. Das ist der gewünschte Rot-Zustand.

- [ ] **Step 1: Den fehlschlagenden Test schreiben**

In `WorkflowExecutorTest.java` direkt hinter `execute_noMatchingExecutor_throwsAndFails()` einfügen:

```java
    @Test
    void execute_blockThrowsError_savesFailedRunInsteadOfLeavingRunning() {
        // Reproduces the 2026-07-27 incident: `mvn test` rewrote target/classes under the
        // running JVM, so lazily loaded classes vanished and blocks died with an Error
        // (not an Exception). The run then stayed RUNNING until the ghost watchdog reaped it.
        when(simpleBlockExecutor.execute(any(BlockDefinition.class), any(ExecutionContext.class)))
                .thenThrow(new NoClassDefFoundError("com/automation/service/todo/GateStatusResolver$1"));

        workflowExecutor.execute(testWorkflow, null);

        WorkflowRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getStatus()).isNotEqualTo(RunStatus.RUNNING);
        assertThat(saved.getError()).containsKey("message");
        assertThat(String.valueOf(saved.getError().get("message")))
                .contains("GateStatusResolver");
    }
```

- [ ] **Step 2: Test laufen lassen und Rot bestätigen**

```bash
cd ai-automation-backend
mvn test -Dtest=WorkflowExecutorTest#execute_blockThrowsError_savesFailedRunInsteadOfLeavingRunning
```

Erwartet: **FAIL**. Der `NoClassDefFoundError` propagiert aus `execute()` heraus in die Testmethode; surefire meldet ihn als Fehler der Testmethode, nicht als Assertion-Failure.

> Hinweis: Solange Task 2 nicht steht, läuft dieses `mvn test` im Haupt-Checkout und schreibt `target/classes` neu — also **nur mit gestopptem Dev-Backend** ausführen, oder das Backend danach neu starten (nach Rückfrage). Genau dieses Henne-Ei-Problem beseitigt Task 2.

- [ ] **Step 3: Den Fix implementieren**

In `WorkflowExecutor.java` den Block ab Zeile 547 ersetzen. Vorher:

```java
        } catch (Exception e) {
            log.error("Workflow execution failed: {}", runId, e);
            if (run != null) {
                run.setStatus(RunStatus.FAILED);
                run.setError(Map.of("message", e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
            }
            activityLogService.error(LogCategory.EXECUTION,
                    "Workflow execution error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()),
                    "WorkflowExecutor", runId, workflowId);
        } finally {
```

Nachher:

```java
        } catch (Throwable t) {
            // Throwable, not Exception: an Error (e.g. NoClassDefFoundError when a build
            // rewrites target/classes under the running JVM) would otherwise slip past every
            // handler, leave the run at RUNNING and only surface as an anonymous ghost run
            // 30 minutes later. See docs/plans/2026-07-27-ghost-run-root-cause-fixes.md
            String detail = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
            log.error("Workflow execution failed: {}", runId, t);
            if (run != null) {
                run.setStatus(RunStatus.FAILED);
                run.setError(Map.of("message", t.getClass().getSimpleName() + ": " + detail));
            }
            activityLogService.error(LogCategory.EXECUTION,
                    "Workflow execution error: " + t.getClass().getSimpleName() + ": " + detail,
                    "WorkflowExecutor", runId, workflowId);
        } finally {
```

Der `catch (InterruptedException e)`-Zweig darüber (`:543-546`) bleibt **unverändert und muss zuerst stehen** — sonst würde der Interrupt-Pfad mitverschluckt.

- [ ] **Step 4: Test laufen lassen und Grün bestätigen**

```bash
cd ai-automation-backend
mvn test -Dtest=WorkflowExecutorTest
```

Erwartet: **PASS**, alle Tests der Klasse. Insbesondere dürfen `execute_blockFails_withStopOnFailure_savesFailedRun` und `execute_noMatchingExecutor_throwsAndFails` nicht brechen — sie prüfen denselben Catch-Zweig für den `Exception`-Fall, und die Fehlermeldung trägt jetzt einen `ClassName: `-Präfix. Falls eine dieser Assertions auf exakten Text prüft, ist das ein echter Bruch: Assertion auf `containsKey("message")` bzw. `contains(...)` anpassen, **nicht** das Präfix wieder entfernen.

- [ ] **Step 5: Committen**

```bash
git add ai-automation-backend/src/main/java/com/automation/service/workflow/WorkflowExecutor.java \
        ai-automation-backend/src/test/java/com/automation/service/workflow/WorkflowExecutorTest.java
git commit -m "fix(workflow): catch Throwable so Errors fail the run instead of ghosting

An Error thrown inside executeBlocks (e.g. NoClassDefFoundError when a build
rewrites target/classes under the running JVM) slipped past catch (Exception).
The finally block unregistered the run from activeExecutions but never set a
final status, so the row stayed RUNNING until the ghost watchdog reaped it
30 minutes later with a generic message.

Refs incident 2026-07-27 (11 runs)."
```

---

### Task 2: `scripts/qa-worktree.sh` — isolierter Build-Worktree

**Files:**
- Create: `scripts/qa-worktree.sh`

**Interfaces:**
- Consumes: nichts aus früheren Tasks.
- Produces: CLI-Kontrakt, auf den Task 3 sich stützt:
  - `scripts/qa-worktree.sh prepare` → legt den Worktree an bzw. synct ihn auf `HEAD` des Haupt-Checkouts, installiert `node_modules` bei geänderter Lockfile, gibt **den Worktree-Pfad auf stdout** aus (alles andere auf stderr).
  - `scripts/qa-worktree.sh path` → gibt nur den Pfad aus, ohne etwas zu verändern.
  - Default-Pfad: `$HOME/.mysuricates/qa-worktree/autotrigger`, überschreibbar via `MYSURICATES_QA_WORKTREE`.

**Designentscheidungen:**
- **Stabiler, wiederverwendeter Pfad** statt Wegwerf-Worktree: `target/` und `node_modules/` bleiben zwischen Runs erhalten, sonst kostet jeder Gate-Lauf einen Kaltstart-Build.
- **Detached HEAD** statt eigener Branch: kollidiert nie mit `.claude/worktrees/`-Branches der Agent-Tools und braucht kein Branch-Management.
- **Getestet wird `HEAD`, nicht der Working Tree.** Uncommittete Änderungen im Haupt-Checkout gehen nicht in den Worktree. Für ein nächtliches Quality Gate auf committetem Code ist das korrekt — und ein bewusster Verhaltenswechsel, der in `docs/entwicklung.md` festgehalten wird (Task 4).
- Kein `npm ci` pro Lauf: Hash der `package-lock.json` wird in `.qa-npm-lock-hash` gestempelt.

- [ ] **Step 1: Das Skript anlegen**

```bash
#!/usr/bin/env bash
# qa-worktree.sh — provides an isolated git worktree for build/test workflows.
#
# Why: the dev backend runs with `-cp target/classes` (a directory, not a JAR) and lazily
# loads classes from disk for its entire lifetime. A `mvn test` in the same checkout
# rewrites target/classes underneath the running JVM -> NoClassDefFoundError -> workflow
# threads die silently -> ghost runs.
# See docs/plans/2026-07-27-ghost-run-root-cause-fixes.md
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
    git -C "$WT" clean -fdq -e target -e node_modules -e data
  fi

  # Reinstall node_modules only when the lockfile actually changed (npm ci is expensive).
  local fe="$WT/ai-automation-frontend"
  if [ -f "$fe/package-lock.json" ]; then
    local lock_hash stamp
    lock_hash="$(sha256sum "$fe/package-lock.json" | cut -d' ' -f1)"
    stamp="$WT/.qa-npm-lock-hash"
    if [ ! -d "$fe/node_modules" ] || [ "$(cat "$stamp" 2>/dev/null || true)" != "$lock_hash" ]; then
      echo "Installing frontend dependencies (lockfile changed or node_modules missing)" >&2
      (cd "$fe" && npm ci) >&2
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
```

- [ ] **Step 2: Ausführbar machen**

```bash
chmod +x scripts/qa-worktree.sh
```

- [ ] **Step 3: `path` prüfen — darf nichts verändern**

```bash
cd "$(git rev-parse --show-toplevel)"
BEFORE=$(stat -c %Y ai-automation-backend/target/classes/com/automation/service/todo/GateStatusResolver.class)
scripts/qa-worktree.sh path
AFTER=$(stat -c %Y ai-automation-backend/target/classes/com/automation/service/todo/GateStatusResolver.class)
[ "$BEFORE" = "$AFTER" ] && echo "OK: main checkout untouched" || echo "FAIL: main target/ changed"
```

Erwartet: Pfadausgabe `…/.mysuricates/qa-worktree/autotrigger` und `OK: main checkout untouched`.

- [ ] **Step 4: `prepare` gegen den laufenden Dev-Backend-Checkout prüfen**

Das ist der eigentliche Regressionsnachweis dieses Tasks: `prepare` (inkl. `npm ci`) darf `target/classes` des Haupt-Checkouts **nicht** anfassen.

```bash
cd "$(git rev-parse --show-toplevel)"
BEFORE=$(find ai-automation-backend/target/classes -name '*.class' -newermt '-1 second' | wc -l; \
         find ai-automation-backend/target/classes -name '*.class' -printf '%T@\n' | sort -n | tail -1)
WT=$(scripts/qa-worktree.sh prepare)
echo "worktree: $WT"
AFTER=$(find ai-automation-backend/target/classes -name '*.class' -newermt '-1 second' | wc -l; \
        find ai-automation-backend/target/classes -name '*.class' -printf '%T@\n' | sort -n | tail -1)
[ "$BEFORE" = "$AFTER" ] && echo "OK: no class file in the live checkout was touched" || echo "FAIL"
test -d "$WT/ai-automation-backend" && echo "OK: worktree populated"
```

Erwartet: `OK: no class file in the live checkout was touched` **und** `OK: worktree populated`. Der erste Lauf dauert wegen `npm ci` einige Minuten.

- [ ] **Step 5: Idempotenz prüfen — zweiter Lauf ohne `npm ci`**

```bash
cd "$(git rev-parse --show-toplevel)"
time scripts/qa-worktree.sh prepare
```

Erwartet: kein „Installing frontend dependencies", Laufzeit unter ~5 Sekunden.

- [ ] **Step 6: Committen**

```bash
git add scripts/qa-worktree.sh
git commit -m "feat(scripts): add qa-worktree.sh for isolated build/test runs

Build workflows must not run mvn in the checkout the dev backend serves its
classes from. This provides a stable, reusable detached worktree under
~/.mysuricates/qa-worktree, keeping target/ and node_modules between runs and
reinstalling npm dependencies only when the lockfile changes."
```

---

### Task 3: Quality-Gate-Workflow auf den Worktree umstellen

**Files:**
- Modify: Workflow `caf831c2-cd95-447f-b349-a695a8f21063` (*AutoPilot Backend Quality Gate*, Projekt AutoPilot) via `PUT /api/v1/workflows/{id}`

**Interfaces:**
- Consumes: `scripts/qa-worktree.sh prepare|path` aus Task 2.
- Produces: keine — Endpunkt der Kette.

**Ist-Zustand (5 Blöcke, alle im Live-Checkout):**

| # | Typ | Kommando |
|---|---|---|
| 0 | shell | `cd ai-automation-backend && mvn test -q` (timeout 600) |
| 1 | conditional | `then_command`: `cd ai-automation-backend && mvn test 2>&1 \| tail -50` |
| 2 | shell | `cd ai-automation-backend && mvn dependency:tree -DoutputType=dot -q 2>&1 \| head -100` |
| 3 | shell | `cd ai-automation-frontend && npm run build 2>&1` |
| 4 | shell | `cd ai-automation-frontend && npm run lint 2>&1` |

Blöcke 0 und 1 sind die Brandstifter (sie schreiben `target/classes` neu). Block 3 schreibt `dist/` in den Live-Checkout — harmlos für die laufende JVM, wird aber mitverschoben, damit das Gate insgesamt keine Schreibzugriffe mehr auf den Live-Checkout hat.

**Soll-Zustand:** ein vorgeschalteter Prepare-Block, danach dieselben fünf Schritte im Worktree.

- [ ] **Step 1: Aktuelle Definition sichern**

```bash
cd "$(git rev-parse --show-toplevel)"
KEY=$(cat ~/.mysuricates/api-key)
curl -s "http://localhost:9090/api/v1/workflows/caf831c2-cd95-447f-b349-a695a8f21063" \
  -H "X-API-Key: $KEY" > /tmp/quality-gate-backup.json
python3 -c "import json;d=json.load(open('/tmp/quality-gate-backup.json'));print(len(d['blocks']),'blocks gesichert')"
```

Erwartet: `5 blocks gesichert`. (MySuricates versioniert Workflows zusätzlich selbst — Rollback notfalls über `POST /api/v1/workflows/{id}/versions/{versionId}/restore`.)

- [ ] **Step 2: Blöcke ersetzen und zurückschreiben**

```bash
cd "$(git rev-parse --show-toplevel)"
KEY=$(cat ~/.mysuricates/api-key)
python3 - "$KEY" <<'PY'
import json, sys, urllib.request

KEY = sys.argv[1]
WF = "caf831c2-cd95-447f-b349-a695a8f21063"
BASE = "http://localhost:9090/api/v1/workflows/" + WF
QA = "./scripts/qa-worktree.sh"   # shell blocks run with cwd = project path

def block(cmd, timeout):
    return {"type": "shell", "name": None,
            "config": {"command": cmd, "timeout_seconds": timeout},
            "requiredCapabilities": [], "skillIds": []}

prep = f'''set -euo pipefail
WT="$({QA} prepare)"
echo "QA-Worktree: $WT"
git -C "$WT" log -1 --oneline'''

def in_wt(sub, cmd):
    return f'''set -euo pipefail
WT="$({QA} path)"
cd "$WT/{sub}"
{cmd}'''

blocks = [
    block(prep, 1800),
    block(in_wt("ai-automation-backend", "mvn test -q"), 1800),
    {"type": "conditional", "name": None,
     "config": {
         "condition": "Der vorherige Maven-Test-Lauf hatte Fehler (Exit-Code != 0). Pruefe den letzten Output.",
         "then_command": in_wt("ai-automation-backend", "mvn test 2>&1 | tail -50"),
         "else_command": None},
     "requiredCapabilities": [], "skillIds": []},
    block(in_wt("ai-automation-backend",
                "mvn dependency:tree -DoutputType=dot -q 2>&1 | head -100"), 600),
    block(in_wt("ai-automation-frontend", "npm run build 2>&1"), 900),
    block(in_wt("ai-automation-frontend", "npm run lint 2>&1"), 600),
]

wf = json.load(open("/tmp/quality-gate-backup.json"))
wf["blocks"] = blocks

req = urllib.request.Request(
    BASE, data=json.dumps(wf).encode(), method="PUT",
    headers={"Content-Type": "application/json", "X-API-Key": KEY})
with urllib.request.urlopen(req) as r:
    out = json.load(r)
print("PUT ok — blocks:", len(out["blocks"]), "version:", out.get("version"))
PY
```

Erwartet: `PUT ok — blocks: 6 version: <n+1>`.

- [ ] **Step 3: Verifizieren, dass kein Block mehr den Live-Checkout anfasst**

```bash
KEY=$(cat ~/.mysuricates/api-key)
curl -s "http://localhost:9090/api/v1/workflows/caf831c2-cd95-447f-b349-a695a8f21063" \
  -H "X-API-Key: $KEY" | python3 -c "
import sys, json
d = json.load(sys.stdin)
bad = []
for i, b in enumerate(d['blocks']):
    for key in ('command', 'then_command', 'else_command'):
        cmd = b.get('config', {}).get(key)
        if not cmd:
            continue
        for line in cmd.splitlines():
            s = line.strip()
            if s.startswith('cd ai-automation'):
                bad.append((i, key, s))
print('Blocks:', len(d['blocks']))
print('FAIL — relative cd into live checkout:', bad) if bad else print('OK — every block resolves its cwd via qa-worktree.sh')
"
```

Erwartet: `Blocks: 6` und `OK — every block resolves its cwd via qa-worktree.sh`.

- [ ] **Step 4: Gate einmal ausführen und die Live-Klassen bewachen**

Der eigentliche Regressionsnachweis des ganzen Plans: ein kompletter Gate-Lauf darf keine einzige `.class`-Datei im Haupt-Checkout anfassen.

```bash
cd "$(git rev-parse --show-toplevel)"
KEY=$(cat ~/.mysuricates/api-key)
SNAP_BEFORE=$(find ai-automation-backend/target/classes -name '*.class' -printf '%T@ %p\n' | sort | sha256sum)

RUN=$(curl -s -X POST "http://localhost:9090/api/v1/workflows/caf831c2-cd95-447f-b349-a695a8f21063/execute" \
  -H "Content-Type: application/json" -H "X-API-Key: $KEY" -d '{}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
echo "run: $RUN"

# bis terminal pollen
while :; do
  ST=$(curl -s "http://localhost:9090/api/v1/executions/$RUN" -H "X-API-Key: $KEY" \
       | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
  echo "  status: $ST"
  [ "$ST" = "RUNNING" ] || break
  sleep 30
done

SNAP_AFTER=$(find ai-automation-backend/target/classes -name '*.class' -printf '%T@ %p\n' | sort | sha256sum)
[ "$SNAP_BEFORE" = "$SNAP_AFTER" ] \
  && echo "PASS: live target/classes untouched by the gate run" \
  || echo "FAIL: the gate still writes into the live checkout"
```

Erwartet: `PASS: live target/classes untouched by the gate run`.

Der Run-Status selbst darf `FAILED` sein (die Tests im Worktree können inhaltlich rot sein) — entscheidend ist hier **ausschließlich** der Snapshot-Vergleich. Wichtig ist außerdem: der Fehler muss jetzt eine echte Meldung tragen, nicht „Ghost run detected by watchdog".

- [ ] **Step 5: Prüfen, dass keine Ghost-Runs mehr entstanden sind**

```bash
KEY=$(cat ~/.mysuricates/api-key)
curl -s "http://localhost:9090/api/v1/executions?size=30&sort=startedAt,desc" -H "X-API-Key: $KEY" \
  | python3 -c "
import sys, json
d = json.load(sys.stdin)
rows = d.get('content', d) if isinstance(d, dict) else d
ghosts = [r for r in rows if 'Ghost run' in str((r.get('error') or {}).get('message', ''))]
print('Ghost runs unter den letzten 30:', len(ghosts))
for g in ghosts:
    print(' ', g['id'][:8], g.get('startedAt'), g.get('workflowName'))
"
```

Erwartet: nur die elf historischen Ghosts vom 2026-07-27 vor 09:31, keine neuen.

---

### Task 4: Dokumentation nachziehen

**Files:**
- Modify: `docs/entwicklung.md`

**Interfaces:**
- Consumes: `scripts/qa-worktree.sh` aus Task 2.
- Produces: keine.

- [ ] **Step 1: Abschnitt in `docs/entwicklung.md` ergänzen**

Direkt hinter den Build-/Test-Kommandos einfügen:

```markdown
## Builds neben dem laufenden Dev-Backend

Das Dev-Backend (`mvn spring-boot:run`) läuft mit `-cp target/classes` — einem **Verzeichnis**,
nicht einer JAR. Die JVM lädt Klassen über ihre gesamte Lebensdauer lazy von Platte nach.

Ein zweiter Maven-Lauf im selben Checkout schreibt `target/classes` neu und zieht der
laufenden JVM damit die Klassen unter den Füßen weg → `NoClassDefFoundError`. Der Fehler ist
**sticky**: der Classloader cached das Load-Failure, nur ein Neustart heilt es. Am 2026-07-27
sind so elf Workflow-Runs gestorben (siehe `docs/plans/2026-07-27-ghost-run-root-cause-fixes.md`).

**Regel:** Kein `mvn` im Haupt-Checkout, solange das Dev-Backend läuft. Stattdessen:

```bash
WT=$(scripts/qa-worktree.sh prepare)   # legt/synct ~/.mysuricates/qa-worktree/autotrigger auf HEAD
cd "$WT/ai-automation-backend" && mvn test
```

Der Worktree ist detached auf `HEAD` des Haupt-Checkouts und bleibt zwischen Läufen bestehen
(`target/` und `node_modules/` werden wiederverwendet). **Uncommittete Änderungen sind darin
nicht enthalten** — was dort läuft, testet den committeten Stand.

Dieselbe Regel gilt für `./scripts/staging.sh test-promote`, das im Haupt-Checkout ein
`mvn clean` ausführt.
```

- [ ] **Step 2: Verifizieren, dass die Doku-Referenz stimmt**

```bash
cd "$(git rev-parse --show-toplevel)"
test -f docs/plans/2026-07-27-ghost-run-root-cause-fixes.md && echo "OK: plan referenced by docs exists"
grep -q "qa-worktree.sh" docs/entwicklung.md && echo "OK: entwicklung.md mentions the script"
```

Erwartet: beide `OK`-Zeilen.

- [ ] **Step 3: Committen**

```bash
git add docs/entwicklung.md
git commit -m "docs: document the QA worktree rule for builds beside the dev backend"
```

---

## Self-Review

**Spec-Abdeckung**

| Befund aus der Analyse | Task |
|---|---|
| Quality Gate baut im Live-Checkout (Ursache) | Task 2 + 3 |
| `catch (Exception)` verschluckt `Error` → stiller Ghost | Task 1 |
| Regel „kein `mvn` neben dem Dev-Backend" nirgends festgehalten | Task 4 |
| Kaltstart-Stampede: 14 Schedules in 9 s | **nicht abgedeckt** — siehe offener Punkt unten |

**Offener Punkt (bewusst außerhalb dieses Plans):** die Suspend-/Resume-Mitigation in `SchedulerService` (Skip-forward statt Backlog-Feuern) greift nur bei einer Lücke zwischen zwei Ticks eines *laufenden* Prozesses, nicht bei einem Kaltstart des Backends. Deshalb feuerten heute 14 Schedules innerhalb von 9 Sekunden. Das hat den Schaden verstärkt, aber nicht verursacht — ohne den `mvn`-Konflikt wären die Runs nur langsam gewesen, nicht tot. Eigenes TODO, eigener Plan.

**Platzhalter-Scan:** keine `TBD`/`später`/„analog zu Task N"-Stellen; jeder Code-Schritt enthält den vollständigen Inhalt.

**Typkonsistenz:** `scripts/qa-worktree.sh` wird in Task 2 mit den Subkommandos `prepare` und `path` definiert; Task 3 und Task 4 verwenden exakt diese beiden Namen. `RunStatus.FAILED` / `RunStatus.RUNNING` entsprechen `entity/RunStatus.java`. `captureLastSavedRun()` existiert bereits in `WorkflowExecutorTest.java:173`.
