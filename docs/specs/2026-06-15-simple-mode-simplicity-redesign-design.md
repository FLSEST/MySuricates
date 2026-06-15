# Simple-Mode-Redesign nach den Prinzipien der Einfachheit

**Datum:** 2026-06-15
**TODO:** `e8e58c58-578d-4bff-a80c-b30fa70c11e7` — „Simple Mode UX überarbeiten"
**Status:** Design (genehmigt im Brainstorming, vor Umsetzungsplan)

## Claude Code Session
`claude --resume ca21c677-6bb6-4f48-9abb-0d87bb575cbd`

---

## 1. Motivation

Der Simple-Mode existiert technisch (v0.4.0), wird aber kaum genutzt und ist UX-seitig unreif. Er ist heute ein reiner **View-Filter**: Pro-Mode minus Features. Dieses Redesign hängt die Vereinfachung bewusst an den **fünf Handlungs-Prinzipien** aus *Simplicity – Prinzipien der Einfachheit* (Brügger/Hartschen/Scherer, GABAL) auf — nicht an Maedas zehn Gesetzen (die „zehn" sind eine häufige Verwechslung).

**Die 5 Prinzipien als Design-Raster:**

| Prinzip | Bedeutung | Anwendung hier |
|---|---|---|
| **Restrukturieren** | Umordnen statt löschen; „Masse und Ausnahme separieren" | 3-Punkte-Menü; in „Meine Workflows" deine Handvoll vorn, der Rest hinter „Alle anzeigen" |
| **Weglassen** | Subtraktion zuerst (Menschen addieren instinktiv) | kein JSON/Shell/Cockpit-Lärm sichtbar; Logs/Metadaten nur on-demand |
| **Ergänzen** | manchmal *hinzufügen*, um zu vereinfachen | Vorschlags-Chips; dezenter Status-Streifen |
| **Ersetzen** | Komplexes durch Einfacheres tauschen | Intent-Eingabefeld statt Workflow-Jargon als Tür |
| **Wahrnehmen** | *gefühlte* Einfachheit; Transparenz, Vertrauen | Fehler sichtbar machen statt verstecken, Klartext-Status |

Leitsatz des Buchs: **nicht weniger Leistung, sondern weniger unnötige Komplexität.**

## 2. Nutzungsdaten (Grundlage der Entscheidungen)

Live aus der REST-API erhoben (2.263 Executions):

- **251 Workflows definiert, im jüngsten Fenster liefen nur 23.** ~90 % schlummern; 74 liefen nie. → Eine 251-Liste ist für Simple unbrauchbar; „Masse vs. Ausnahme" ist zwingend.
- **`shell` + `simple` = ~90 % des Ausführungsvolumens.** `agentic` breit, aber low-volume. `sub-workflow`/`document`/`while`/`switch` praktisch tot; `completion-triggers`: 0.
- **Echter End-User-Wert clustert eng:** LernApp (Lehrplan→Lernpakete), persönliche „Radar"-Workflows, Job-Automation — eine Handvoll, keine 251.
- **~48 % Fehlerquote** im jüngsten Fenster (Daten-Pipelines teils 100 % fail). → Fehler-Sichtbarkeit ist Pflicht, nicht Kür.

## 3. Getroffene Entscheidungen (Brainstorming)

1. **Kern-Job:** „Ausführen & Ergebnis" als Basis — vorhandenen Modus *weiterentwickeln*, nicht neu erfinden.
2. **Menü-Zuschnitt: Variante 3 „Geführt zuerst"** — drei Menüpunkte: `Was möchtest du tun?` · `Meine Workflows` · `Ergebnisse`.
3. **Einstieg: Verhalten A „Intelligenter Starter"** — Intent tippen → bestehende Workflows werden gematcht und mit ▶ angeboten; kein Treffer → Fallback auf Liste.
4. **Zielgruppe: Mischung** — Ziel ist „übergabe-fähig für Nicht-Techniker" (Phase 2), gebaut wird **additiv**, Phase 1 = ruhiger geführter Modus auf View-Ebene.
5. **Harte Leitplanke: Pro bleibt unangetastet; Pro-Design wird wiederverwendet.**

## 4. Constraints (nicht verhandelbar)

- **C1 — Pro unverändert:** Keine Änderung an Pro-Seiten, -Komponenten, -Routen oder -Verhalten. Verifizierbar: Pro-Routen rendern byte-gleich wie vorher.
- **C2 — Pro-Design wiederverwenden:** Simple ist keine eigene Optik. Es komponiert bestehende Komponenten und Theme-Tokens neu (gleiche Cards, Buttons, Status-Badges, Run-Ansichten), nur kuratiert/reduziert.
- **C3 — Geteilte Komponenten nur additiv:** Falls eine *shared* Komponente einen „simple/kompakt"-Modus braucht, wird das per optionalem Prop ergänzt, der auf das **heutige Pro-Verhalten defaultet**. Damit bleibt Pro garantiert identisch.
- **C4 — Kein Backend-Eingriff in Phase 1:** Phase 1 nutzt nur vorhandene Endpoints + Client-Logik. Backend-Erweiterungen (semantisches Matching, Guards) sind Phase 2.
- **C5 — Projektregeln:** Tests grün halten (`npx tsc --noEmit` + `npm run lint`, betroffene E2E); de.ts/en.ts immer gemeinsam pflegen; Frontend-Änderungen im Browser verifizieren.

## 5. Design

### 5.1 Navigation — *Restrukturieren*

Simple-Mode zeigt drei Menüpunkte (statt heute `/`, `/workflows`, `/runs` mit Pro-Labels):

| Reihenfolge | Route | Simple-Label | Job |
|---|---|---|---|
| 1 | `/` | **Was möchtest du tun?** | geführter Einstieg (neu) |
| 2 | `/workflows` | **Meine Workflows** | ausführen |
| 3 | `/runs` | **Ergebnisse** | Resultate & Fehler |

Plus: minimale Einstellungen, Pro-Umschalter (bleibt wie heute in der Sidebar).

**Umsetzung:** `SIMPLE_MODE_ROUTES` in `src/components/layout/Sidebar.tsx:31` bleibt bei diesen drei Routen; die **Labels** werden im Simple-Mode überschrieben (Label-Override-Map, nur wenn `simpleMode`), Pro-Labels unverändert (C1).

### 5.2 Startseite „Was möchtest du tun?" — *Ersetzen + Ergänzen*

Neue Seite **`SimpleHome.tsx`**, im Simple-Mode an `/` gemountet (ersetzt `SimpleDashboard` als Simple-`/`-Route; `SimpleDashboard.tsx` bleibt als Datei bestehen, wird aber nicht mehr geroutet — oder als „Status"-Detail wiederverwendbar).

Aufbau (siehe Mockup `.superpowers/brainstorm/.../home-mockup.html`):
- Begrüßung + ein großes **Intent-Eingabefeld** („z. B. neue Jobs in Zürich finden"). *Ersetzt* Workflow-Jargon durch Absicht.
- **„Häufig genutzt"-Chips** — Top-N Workflows nach `runCount`/Recency aus der Workflows-API. *Ergänzung* gegen Leeres-Feld-Angst (das C-Element als Vorschläge über dem Feld).
- **Status-Streifen** unten: „Heute: 3 grün · 1 fehlgeschlagen → Ergebnisse ansehen". *Ergänzung*, die Fehler nicht versteckt (→ *Wahrnehmen*).

**Verhalten A — Matching (Phase 1, ohne Backend):**
- Reine Client-Funktion `matchWorkflows(intent, workflows)` → rankt nach Treffer in Name/Beschreibung/Tags, gewichtet mit `runCount`/Recency. Liefert Top-Matches mit ▶.
- Treffer auswählen → bestehender **Execute-Dialog** (shared, bereits variablenbasiert, kein JSON) öffnet sich.
- **Kein Treffer / niedriger Score:** Klartext-Fallback „Keiner deiner Workflows passt genau — alle anzeigen" (→ `Meine Workflows`).
- **Naht für Phase 1b/2:** `matchWorkflows` ist als isolierte, testbare Funktion gekapselt; sie kann später durch einen Assistant-/semantischen Endpoint ersetzt werden, ohne die UI zu ändern.

### 5.3 Meine Workflows — *Restrukturieren (Masse/Ausnahme)*

Weiterentwicklung von **`src/pages/SimpleWorkflows.tsx`**:
- **Standard = deine Handvoll**: angepinnte ∪ Top-nach-`runCount`/Recency. **Nicht** die 251-Liste.
- **„Alle anzeigen"** klappt den Rest auf (die Ausnahme) — nutzt das vorhandene Listen-Rendering.
- **Pins:** Phase 1 client-seitig in `settingsStore` (localStorage), kein Backend (C4). Naht für späteren per-User-Backend-Pin.
- Jede Karte (bestehende Workflow-Card wiederverwenden): Name, ein Satz, ▶, **Status-Punkt des letzten Laufs** (grün/rot, vorhandenes Status-Badge) → *Wahrnehmen*.
- Start = bestehender Execute-Dialog, **kein JSON/Shell sichtbar** (heute schon der Fall — wird verifiziert).

### 5.4 Ergebnisse — *Wahrnehmen*

Neue Simple-Liste **`SimpleRuns.tsx`** für `/runs` (heute nutzt Simple die volle `Runs.tsx`):
- Letzte Läufe, **Fehler nach oben + klar markiert** (greift die 48 % auf).
- **Klartext statt Stacktrace**: „Fehlgeschlagen — nochmal versuchen?" mit Re-Execute-Aktion (bestehender Execute-Pfad).
- Detail = bestehende **`SimpleWorkflowExecution.tsx`** (strippt bereits Logs/Terminal/Metadaten); „Details"-Aufklappen für den Pro-Blick optional.
- Run-Cards/Status reuse aus den vorhandenen Shared-Komponenten.

### 5.5 Einstellungen + Feinschliff — *Weglassen*

- **Settings-Leak schließen:** In `src/pages/Settings.tsx` (heute Sektions-Hiding um Z. 54–89) im Simple-Mode nur noch **Theme / Sprache / API-Key / Pro-Wechsel** zeigen. Eingriff ist additiv (mehr Hiding bei `simpleMode`); Pro (`!simpleMode`) unverändert (C1).
- **Onboarding:** Einmaliges, dezentes Intro beim ersten Wechsel in den Simple-Mode („Du bist im Simple-Modus — hier führst du deine Workflows aus.") Flag in `settingsStore`. Neue kleine Simple-only-Komponente.
- **Layout-Sprung glätten:** Simple-Seiten auf konsistente Breite bringen (heute `max-w-2xl` vs. Pro `max-w-4xl`).

## 6. Architektur / Isolation (Units)

| Unit | Verantwortung | Hängt ab von | Neu/Geändert |
|---|---|---|---|
| `SimpleHome.tsx` | geführter Einstieg | Workflows-Query, `matchWorkflows`, Execute-Dialog, `settingsStore` | **neu** |
| `matchWorkflows` (util/hook) | reine Funktion: (intent, workflows) → ranked | — | **neu**, isoliert testbar |
| `SimpleWorkflows.tsx` | kuratierte Liste (Masse/Ausnahme) | Workflows-Query, Pins, Status-Badge | geändert |
| `SimpleRuns.tsx` | Ergebnisse, Fehler zuerst | Executions-Query, Run-Card, Status | **neu** |
| `SimpleOnboarding` | Einmal-Intro | `settingsStore` | **neu** |
| `Sidebar.tsx` | Label-Override im Simple-Mode | `simpleMode` | geändert (additiv) |
| `Settings.tsx` | Sektions-Hiding verschärfen | `simpleMode` | geändert (additiv) |
| `settingsStore.ts` | Pins + Onboarding-Flag | — | geändert (additiv) |
| `App.tsx` | `/`-Wrapper → `SimpleHome` im Simple | `simpleMode` | geändert |
| `de.ts` / `en.ts` | neue Strings | — | geändert (gemeinsam) |

**Datenfluss Startseite:** Intent-Text → `matchWorkflows(intent, workflows)` → Ranked-Liste → ▶ → Execute-Dialog (Variablen) → POST `/api/v1/workflows/{id}/execute` (bestehender Endpoint) → Navigation zur Run-Ansicht.

## 7. Phasen (die *Mischung*)

- **Phase 1 (Umsetzungsziel jetzt):** Alles in §5/§6 auf View-Ebene. Reuse von Pro-Komponenten, kein Backend-Eingriff, Pro unverändert.
- **Phase 2 (dokumentiert, nicht in diesem Plan):**
  - **Backend-enforced Guards** (read-only / Capability-Schutz, gefährliche Aktionen serverseitig sperren) → echt übergabe-fähig. *Guards im Code, nicht nur ausgeblendet.*
  - **Semantisches Matching** über vorhandenen `AssistantService` statt Client-Keyword-Match.
  - **Sichere Übergabe:** per-User-Lock des Modus, Pins serverseitig.

## 8. Fehlerbehandlung

- **Startseite:** kein Match → Klartext-Fallback; keine Workflows vorhanden → freundlicher Empty-State mit Verweis auf Pro/Factory.
- **Ergebnisse:** fehlgeschlagener Lauf → Klartext + „nochmal versuchen"; Lade-/Fehlerzustände nutzen bestehende `ErrorState`/Loading-Komponenten.
- **Matching robust:** leerer Intent → zeigt nur Chips; Sonderzeichen/sehr lange Eingaben → defensiv getrimmt.

## 9. Testing

- **Unit:** `matchWorkflows` (Intent→Ranking, Tie-Break über `runCount`, leerer/kein Treffer); Kurations-Logik (Pinned ∪ Top, „Alle anzeigen").
- **Typecheck/Lint:** `npx tsc --noEmit` + `npm run lint` (grün).
- **Browser-Verifikation:** Toggle Simple → 3 Menüpunkte → Intent tippen → Match → ▶; Fehler-zuerst in Ergebnisse; Settings zeigt nur 4 Dinge.
- **Regression Pro:** Stichprobe, dass Pro-Routen/Labels/Settings unverändert sind (C1) — additive Props defaulten auf Pro.
- **E2E (optional):** Simple-Happy-Path als Playwright-Spec.

## 10. Out of Scope

- Jegliche Pro-Mode-Änderung (C1).
- Backend-Endpoints, DB-Migrationen, Guards (→ Phase 2).
- Neue Workflow-Block-Typen oder Executor-Logik.
- Geführter Assistent mit Rückfragen (Verhalten B) und reines Ziel-Kachel-Modell (Verhalten C) — A ist die Basis; B/C sind spätere Optionen.

## 11. Offene Punkte / Annahmen

- **Pins-Speicher:** Phase 1 localStorage (Annahme: akzeptabel, da Single-User-Dev). Backend-Pin = Phase 2.
- **`SimpleDashboard.tsx`:** wird in Phase 1 aus dem `/`-Routing genommen; Entscheidung offen, ob löschen oder als optionale „Status"-Sicht behalten. Vorschlag: behalten, ungeroutet, bis Bedarf geklärt.
- **Chip-Quelle:** Top-N nach `runCount` global vs. pro aktivem Projekt — Vorschlag global, da Simple projektagnostisch.
