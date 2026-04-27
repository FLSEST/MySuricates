# Code-Review-Report

**Diff-Range**: `HEAD~5..HEAD` (Fallback, da Branch == main)
**Datum**: 2026-04-24
**Reviewer**: MySuricates Code-Review-Agent

## Severity-Definitionen

- **Critical**: Security-Luecke, Datenverlust, Crash im Prod-Pfad — sofort fixen.
- **High**: Funktionaler Bug, ernsthaftes Performance-Problem, verletzte Invariante — vor Release fixen.
- **Medium**: Inkonsistenz, schlechtes Error-Handling, vermeidbare Duplikation — in Kuerze fixen.
- **Low**: Stil, Kleinigkeit, Nice-to-have.

## Umfang

- **Commits** (5):
  - `b57d303` fix(assistant): auto-fetch on announcement text, not just blank responses
  - `75f5899` feat(workflow): add `{{ run_timestamp }}` path-safe timestamp variable
  - `20644ad` feat(palette): phase 5.2 — Tab context switch & Alt+1–9 row shortcuts
  - `b635131` test(workflow): lock in `{{ project_path }}` + system variable seeding
  - `a5c912c` feat(execution): per-block container_image with mount support + system variables
- **Dateien geaendert**: 20 (+555 / -60 Zeilen), davon 1 neu (`Dockerfile.agent-java`).
- Betroffen: Backend-Executoren (Shell/Retry/ForEach/While/Switch), `CommandExecutorService`, `ContainerShellService`, `AssistantService`, `WorkflowExecutor`, Frontend `CommandPalette`, i18n, Doku.

## Zusammenfassung

Der Diff fuehrt drei sauber voneinander getrennte Features ein:
(1) Pro-Block `container_image` + bind-mounts inkl. Template-Resolution und Weiterreichung durch Retry/ForEach/While/Switch,
(2) path-safe `{{ run_timestamp }}` plus neue System-Variablen `home_dir`/`user` im Workflow-Executor,
(3) Tab-Context-Switch + `Alt+1..9`-Shortcuts in der Command Palette,
plus (4) einen Assistant-Fix, der Stall-Texte (`Ich schaue mir …`) nicht mehr als finale Antwort durchgehen laesst.

Tests wurden fuer jede Backend-Aenderung ergaenzt. Gesamteindruck: solide gemacht, defensiv geschrieben, gute Test-Abdeckung. Findings: 0 Critical, 1 High, 4 Medium, 4 Low.

## Findings

### Critical (0)

Keine Findings.

### High (1)

#### H1 — Arbitrary Host-Mounts sind nicht gegatet (Workflow-Autor kann beliebige Host-Pfade ins Container einbinden)
**Datei**: `ai-automation-backend/src/main/java/com/automation/service/cli/ContainerShellService.java:152-159` (+ call-site `ShellBlockExecutor.java:49-56`)

**Snippet** (`ContainerShellService.buildDockerCommand`):
```java
// Extra bind-mounts from block config (already template-resolved by caller)
if (extraMounts != null) {
    for (String mount : extraMounts) {
        if (mount == null || mount.isBlank()) continue;
        cmd.add("-v");
        cmd.add(mount);
    }
}
```

**Problem**:
`mount_docker_socket` steht bewusst hinter der Property `automation.execution.allow-docker-socket-mount` (siehe Zeile 59-65 derselben Datei), weil Socket-Mount trivialen Container-Escape erlaubt. Die neue generische `mounts`-Liste umgeht diesen Gating-Mechanismus: jeder Workflow-Autor kann `"/etc:/etc:rw"`, `"/var/run/docker.sock:/var/run/docker.sock"`, `"/:/host:rw"` oder `/home/<anderer-user>` ins Ephemeral-Container bind-mounten. Das Container-User-Flag `--user $(hostUid)` begrenzt die Reichweite zwar auf die Rechte des Host-Users, aber fuer einen Single-User-Developer-Host sind das _alle_ Dateien dieses Users (SSH-Keys, Browser-Profile, `~/.config`, andere Projekte). Damit ist `mounts` de-facto ein allgemeinerer Eskalationspfad als der explizit gegatete Socket-Mount.

Sub-issue: `/var/run/docker.sock` laesst sich ueber `mounts: ["/var/run/docker.sock:/var/run/docker.sock"]` einmounten und umgeht so die `allowDockerSocketMount`-Policy komplett.

**Vorschlag**:
- Zweite Property einfuehren: `automation.execution.allow-extra-mounts` (default `false` oder `true` nur fuer local-dev-Profile), analog zu `allow-docker-socket-mount`.
- Blacklist fuer Ziel-Pfade: mindestens `/var/run/docker.sock` explizit ablehnen, optional auch `/`, `/etc`, `/root`.
- In `ActivityLogService.warn(LogCategory.SECURITY, ...)` jede neue Mount-Aktivierung loggen (Konvention existiert bereits fuer Socket-Mount, Zeile 66-70).

Beispiel-Skelett:
```java
private static final Set<String> FORBIDDEN_MOUNT_TARGETS =
        Set.of("/var/run/docker.sock", "/", "/etc", "/root");

for (String mount : extraMounts) {
    if (mount == null || mount.isBlank()) continue;
    String hostPath = mount.split(":", 2)[0];
    if (FORBIDDEN_MOUNT_TARGETS.contains(hostPath)) {
        activityLogService.warn(LogCategory.SECURITY,
                "Forbidden mount target rejected: " + hostPath, "ContainerShellService", null);
        continue;
    }
    if (!allowExtraMounts) {
        activityLogService.warn(LogCategory.SECURITY,
                "Extra mount requested but denied by policy", "ContainerShellService", null);
        continue;
    }
    cmd.add("-v");
    cmd.add(mount);
}
```

---

### Medium (4)

#### M1 — Mount-Listen-Parsing dreifach dupliziert (inkonsistente Resolve-Regeln)
**Dateien**:
- `ai-automation-backend/src/main/java/com/automation/executor/blocks/ShellBlockExecutor.java:89-104` (`resolveMounts`, _mit_ Template-Resolution)
- `ai-automation-backend/src/main/java/com/automation/service/cli/CommandExecutorService.java:296-305` (`extractResolvedMounts`, _ohne_ Template-Resolution)
- `ai-automation-backend/src/main/java/com/automation/service/cli/CommandExecutorService.java:318-334` (Inline-Loop in `buildResolvedShellBlock`, _mit_ Template-Resolution)

**Snippet** (`CommandExecutorService.extractResolvedMounts`):
```java
private static List<String> extractResolvedMounts(Object raw) {
    if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
    List<String> out = new java.util.ArrayList<>(list.size());
    for (Object e : list) {
        if (e == null) continue;
        String s = e.toString();
        if (!s.isBlank()) out.add(s);
    }
    return out;
}
```

**Problem**:
Dreimal fast dieselbe Schleife (List-Coercion, Null-Skip, Blank-Skip). Zwei Varianten machen Template-Resolution, eine nicht — was _scheint_ bewusst zu sein (die wrapper blocks resolven vorher via `buildResolvedShellBlock`, der direkte Shell-Block resolvet in `ShellBlockExecutor.resolveMounts`). Aber wenn jemand in Zukunft einen weiteren Block-Executor ergaenzt, wird er sich eine der drei Varianten kopieren und ggf. die falsche — `extractResolvedMounts` resolvet _nicht_, obwohl der Name ("Resolved") suggeriert, dass es vorher erledigt wurde. Das ist ein Fallstrick.

**Vorschlag**:
Gemeinsame statische Helfer-Methode in `ContainerShellService` (oder neuer Utility-Klasse `MountSpec`):
```java
public static List<String> resolveMounts(Object raw, ExecutionContext context) {
    if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
    List<String> out = new ArrayList<>(list.size());
    for (Object e : list) {
        if (e == null) continue;
        String s = e.toString();
        if (s.isBlank()) continue;
        out.add(context == null ? s : context.resolveTemplate(s));
    }
    return out;
}
```
Dann sowohl von `ShellBlockExecutor.execute`, `CommandExecutorService.executeStepAsShell` als auch von `buildResolvedShellBlock` darauf zurueckgreifen. Ergebnis: eine kanonische Stelle, eine Policy.

---

#### M2 — `hasSubstantiveAnswer` verwirft legitime Kurz-Antworten (< 40 chars) ohne `<actions>`
**Datei**: `ai-automation-backend/src/main/java/com/automation/service/AssistantService.java:378-391`

**Snippet**:
```java
private boolean hasSubstantiveAnswer(String rawOutput, String nonFetchText) {
    if (nonFetchText == null || nonFetchText.isBlank()) return false;
    if (rawOutput != null && rawOutput.contains("<actions>")) return true;
    String trimmed = nonFetchText.trim();
    if (trimmed.length() >= ANSWER_TEXT_MIN_CHARS) return true;
    // Short text — treat as announcement filler and continue the fetch loop.
    return !ANNOUNCEMENT_PATTERN.matcher(trimmed).find() && trimmed.length() >= 40;
}
```

**Problem**:
Die Schwelle `trimmed.length() >= 40` wirft sehr kurze, aber vollstaendige Antworten weg — typische Faelle: `"Nein, der Run ist noch nicht fertig."` (35 chars), `"Der Workflow existiert nicht mehr."` (35 chars), `"Ja, es laufen gerade 3 Runs."` (29 chars). Diese landen im Fetch-Loop, was einen zusaetzlichen CLI-Round-Trip kostet, den User aber das _richtige_ Antwort-Text-Material bereits nach Runde 1 haette. Der Assistant-Prompt (Diff an `assistant-system-prompt.md`) sagt explizit "Either answer directly" — kurze direkte Antworten sind der Wunsch-Zustand, werden aber durch diese Heuristik bestraft.

Dazu kommt: Das Regex `ANNOUNCEMENT_PATTERN` ist auf Deutsch gehardcoded. Ein englischer Chat ("I'll check the run.") wuerde nicht matchen, aber `length >= 40` auch nicht fuer "Run is done." Dann haengt die Entscheidung allein am 40-char-Threshold. Der Assistent ist system-prompt-deutsch, Regex passt also in der Praxis — aber wenn Sprache mal ueber Prompt-Overrides variiert, bricht die Heuristik.

**Vorschlag**:
- Primaer-Signal: "Enthaelt der rawOutput _ueberhaupt_ einen `<fetch>`-Tag?" Wenn nein, ist der Text IMMER substantiell (Fetch-Loop kann nicht fortgesetzt werden, es gibt nichts zu fetchen).
- Sekundaer: `<actions>`-Check (wie jetzt).
- Tertiaer: Wenn `<fetch>` UND Text UND Text matched `ANNOUNCEMENT_PATTERN` → Filler, fetch loop. Sonst: substantiell, egal wie kurz.
- Das 40-char-Kriterium ersatzlos streichen — `ANNOUNCEMENT_PATTERN` ist die eigentliche Semantik.
- `ANSWER_TEXT_MIN_CHARS` + `ANNOUNCEMENT_PATTERN` in `ExecutionSettingsService` als konfigurierbar ausrollen (analog zu Memory-Feedback `feedback_timeouts_in_settings.md`, Sinngemaess auf Heuristik-Schwellen uebertragen).

Beispiel:
```java
private boolean hasSubstantiveAnswer(String rawOutput, String nonFetchText) {
    if (nonFetchText == null || nonFetchText.isBlank()) return false;
    if (rawOutput == null || !rawOutput.contains("<fetch")) return true;
    if (rawOutput.contains("<actions>")) return true;
    String trimmed = nonFetchText.trim();
    if (ANNOUNCEMENT_PATTERN.matcher(trimmed).find()) return false;
    return trimmed.length() >= ANSWER_TEXT_MIN_CHARS || trimmed.length() >= 20;
}
```

---

#### M3 — `cycleContext` in Schleife innerhalb eines `onClick` ist fragil (race via Stale Closure)
**Datei**: `ai-automation-frontend/src/components/palette/CommandPalette.tsx:308-315`

**Snippet**:
```jsx
onClick={() => {
    const diff = (CONTEXT_ORDER.indexOf(cid) - CONTEXT_ORDER.indexOf(contextId) + CONTEXT_ORDER.length) % CONTEXT_ORDER.length;
    if (diff === 0) return;
    for (let i = 0; i < diff; i += 1) cycleContext(1);
}}
```

**Problem**:
Funktioniert dank `setContextId((c) => …)`-Updater-Form zwar korrekt, ist aber eine unnoetig komplizierte Rundreise: wenn der User auf "Project" klickt, waehrend "Workflow" aktiv ist, wird `cycleContext(1)` zweimal aufgerufen, d.h. React fuehrt zwei sequenzielle State-Updates durch (`workflow → run → project`). Zwischenrender ist nicht garantiert batched in allen React-Versionen (batching gilt innerhalb eines Event-Handlers ab React 18 durchweg, aber es ist trotzdem fragil: jemand, der das liest, haelt es intuitiv fuer einen Off-by-One-Bug).

**Vorschlag**:
Direkt auf das Ziel setzen, eine `setContext(id)`-Variante ergaenzen:
```jsx
const setContext = (id: PaletteContextId) => setContextId(id);
// …
onClick={() => setContext(cid)}
```
Und `cycleContext` nur fuer Tab/Shift-Tab verwenden. Spart 5 Zeilen modulare Arithmetik und macht den Tab-Handler unabhaengig vom Button-Handler.

---

#### M4 — Button-Labels basteln sich ihr Label aus `triggerLabel.replace(/…$/, '')` — i18n-Kopplung
**Datei**: `ai-automation-frontend/src/components/palette/CommandPalette.tsx:320-322`

**Snippet**:
```jsx
{t.commandPalette.contexts[cid].triggerLabel.replace(/…$/, '').replace(/\.\.\.$/, '')}
```

**Problem**:
Die Tab-Button-Labels ("Workflow suchen oder starten…") werden aus den Trigger-Labels recycelt und per Regex die trailing ellipsis entfernt. Das koppelt zwei UI-Konzepte (Shortcut-Trigger-Label vs. Tab-Label) ueber String-Manipulation. Aenderungen am `triggerLabel` (z.B. um ein anderes Verb) schlagen durch, und Uebersetzungen in Sprachen, in denen die Ellipsis woanders steht, brechen. Bereits jetzt werden beide Formen (`…` U+2026 + `...`) gestrippt, d.h. der Autor wusste, dass das fragil ist.

**Vorschlag**:
Eigene i18n-Keys pro Context, z.B. `t.commandPalette.contexts.workflow.tabLabel` (`"Workflow"`, `"Run"`, `"Project"`), und in beiden `de.ts`/`en.ts` ergaenzen. Halbes Dutzend Zeilen; dafuer wird die Abhaengigkeit explizit.

---

### Low (4)

#### L1 — `rawOutput.contains("<actions>")` ist ein reiner Substring-Match (false-positive-Potenzial)
**Datei**: `ai-automation-backend/src/main/java/com/automation/service/AssistantService.java:382`

Substring-Match auf `<actions>` triggert auch dann, wenn das Modell den Tag-Begriff in freiem Text verwendet ("Ich werde dir `<actions>` zuruecksenden, sobald ich die Daten habe…"). Unwahrscheinlich, aber gratis zu haerten:
```java
if (rawOutput != null && rawOutput.matches("(?s).*<actions>\\s*\\[.*")) return true;
```
Oder via `toolExecutor.extractTools(rawOutput, …)` pruefen, was sowieso spaeter im Flow passiert — das Ergebnis cachen und einmalig verwenden.

---

#### L2 — Redundanter Pattern-Flag in `ANNOUNCEMENT_PATTERN`
**Datei**: `ai-automation-backend/src/main/java/com/automation/service/AssistantService.java:367-369`

```java
Pattern.compile(
    "(?is)^\\s*(ich\\s+(schau|hole|lade|pr(ü|u)f|…).{0,200}",
    Pattern.CASE_INSENSITIVE);
```
Das Inline-Flag `(?is)` enthaelt bereits `CASE_INSENSITIVE`. Der zusaetzliche `Pattern.CASE_INSENSITIVE`-Parameter ist redundant (funktional harmlos, nur stilistisch). Kann entfernt werden.

---

#### L3 — `user` System-Variable exponiert OS-Username in Workflow-Context / Logs
**Datei**: `ai-automation-backend/src/main/java/com/automation/service/workflow/WorkflowExecutor.java:224`

```java
context.setVariable("user", System.getProperty("user.name", ""));
```
Der Host-Username landet damit in `ExecutionContext`, der pro Step persistiert und ggf. in `ActivityLogService`/Frontend-Run-Details angezeigt wird. Fuer Single-User-Dev-Setup unkritisch, fuer Multi-User-Server aber Datenleck-Vektor (Username ist oft Email-Praefix). Kommentar im Diff sagt "user-agnostic configs" — Ironie. Entweder dokumentieren (in `WORKFLOW-GUIDE.md` explizit nennen, welche Variablen automatisch gefuellt werden und warum), oder Variablennamen umbenennen (`host_user` statt `user`, um Ueberschneidungen mit einer evtl. echten "user"-Variable aus dem Workflow-Kontext zu vermeiden).

Dazu: `SYSTEM_VAR_KEYS` (Zeile 67) reserviert diese Namen gegen User-Overrides — aber `home_dir` und `user` wurden in dem Set _nicht_ ergaenzt. Das heisst, ein Workflow kann sie ueberschreiben (legal) — aber dann bricht `{{ home_dir }}` in `mounts`-Strings. Wenn die Absicht ist, dass das reserviert sein soll, muessen sie ins Set.

**Vorschlag**:
```java
private static final Set<String> SYSTEM_VAR_KEYS = Set.of(
        "workflow_run_id", "workflow_id", "workflow_name", "project_path",
        "model", "provider", "started_at", "run_timestamp",
        "home_dir", "user",            // ← hinzufuegen
        "previous_report", "open_todos",
        "workflow_version", "workflow_changed_since_last_run"
);
```

---

#### L4 — Assistant-Regex: `pr(ü|u)f` und `prufe` doppelt
**Datei**: `ai-automation-backend/src/main/java/com/automation/service/AssistantService.java:368`

Das Pattern hat `pr(ü|u)f` UND separat `prufe` — letzteres ist von `pr(ü|u)f` bereits abgedeckt (Pattern matched "pruf" und "prüf", auch ohne das trailing `e`, weil die Alternativen "schau|hole|…" Praefixe sind und `.{0,200}` den Rest schluckt). Kann konsolidiert werden:
```regex
ich\s+(schau|hole|lade|pr(ü|u)f|analysier|check|verifizier)
```

---

## Positives

1. **Test-Abdeckung**: Jede Backend-Aenderung hat einen neuen Test (Container-Mode vs. Host-Mode, `buildResolvedShellBlock` mit Template-Resolution, System-Variablen-Seeding, Run-Timestamp-Format, Announcement-Filter). Das `ContainerShellServiceTest` testet sogar die Private-Method via Reflection — konsistent mit dem bestehenden Stil der Datei.
2. **Regression-Fix mit Test**: `chat_announcementTextWithFetchStillAutoFetches` dokumentiert die beobachtete Regression im Test-Namen selbst — sofort verstaendlich, warum der Test existiert.
3. **Dockerfile.agent-java**: User-Home explizit mit `chmod a+rwX` vorbereitet, damit das `--user`-Override zur Laufzeit nicht auf ENOENT/EACCES laeuft. Saubere Verankerung.
4. **Template-Variable `run_timestamp`**: UTC + `yyyyMMdd-HHmmss` ist genau das richtige Path-Safe-Format (kein `:`, keine Zeitzone). Doku-Eintrag in `WORKFLOW-GUIDE.md` passt.
5. **Backwards-Kompatibilitaet**: Die neue `ContainerShellService.execute`-Signatur mit `extraMounts` behaelt die 5-Arg-Variante als Overload. Bestehende Aufrufer (alle bis auf Shell/wrapper blocks) kompilieren unveraendert weiter.

## Empfehlungen fuer Folgearbeit

1. **[HIGH] Mount-Gating einziehen** (H1): Property `automation.execution.allow-extra-mounts`, `/var/run/docker.sock` als Forbidden-Target blacklisten, Security-Log pro Mount. Sonst ist der Socket-Mount-Schutz umgehbar.
2. **[MEDIUM] Mount-Resolve-Helfer konsolidieren** (M1): Drei Stellen auf eine Utility-Methode reduzieren.
3. **[MEDIUM] `hasSubstantiveAnswer` vereinfachen** (M2): Primaer am `<fetch>`-Vorhandensein entscheiden, Laenge-Schwelle lockern oder streichen; 40-char-Regel wirft legitime Kurzantworten weg.
4. **[LOW] `SYSTEM_VAR_KEYS` ergaenzen** um `home_dir` und `user` (L3), damit die Reserved-Variables-Policy konsistent bleibt.
5. **[LOW] i18n der Tab-Labels entkoppeln** (M4/L-nahe): eigene `tabLabel`-Keys statt Regex-Stripping.
6. Optional: `ANSWER_TEXT_MIN_CHARS`/`ANNOUNCEMENT_PATTERN` in `ExecutionSettingsService` exponieren, analog zur bestehenden Timeouts-Konvention (Memory-Eintrag `feedback_timeouts_in_settings.md`).
