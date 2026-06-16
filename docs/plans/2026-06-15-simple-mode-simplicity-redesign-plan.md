# Simple-Mode-Redesign (Phase 1) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Den Simple-Mode von „Pro minus Features" zu einem geführten, ruhigen Ausführungs-Modus umbauen — geführter Einstieg („Was möchtest du tun?"), kuratierte „Meine Workflows" (Masse/Ausnahme), „Ergebnisse" mit Fehlern zuerst — ohne Pro zu verändern.

**Architecture:** Reine Frontend-Änderung in `ai-automation-frontend/`. Neue Simple-Seiten + Utils, Wiring über die bestehenden Route-Wrapper in `App.tsx` und den Nav-Filter in `Sidebar.tsx`. Pro-Komponenten werden wiederverwendet (gleiche Cards/Buttons/Badges); geteilte Komponenten werden nur additiv erweitert (Default = heutiges Pro-Verhalten). Kein Backend-Eingriff (Phase 1).

**Tech Stack:** React + TypeScript, Zustand (`settingsStore`, persist key `app-settings`), TanStack Query (`useWorkflows`, `useAllRuns`, `useExecuteWorkflow`), i18n (`useT`, typisiert via `TranslationKeys`), Vitest + Testing Library, TailwindCSS.

**Spec:** `docs/specs/2026-06-15-simple-mode-simplicity-redesign-design.md`
**TODO:** `e8e58c58-578d-4bff-a80c-b30fa70c11e7` (IN_PROGRESS)

## Test-Strategie (projektkonform)

- **Pure Logik** (`matchWorkflows`, `curateWorkflows`, `latestRunStatusMap`) → volle TDD mit Vitest (Pattern: `src/utils/*.test.ts`).
- **Wiring** (Store, Routen, Nav, Seiten, Settings) → `npx tsc --noEmit` + `npm run lint` + manuelle Browser-Verifikation auf `localhost:3000`. (Das Projekt unit-testet Stores/Seiten nicht — bestehendes Pattern.)
- **Pro-Regression:** Nach jeder geteilten-Komponenten-Änderung mit `simpleMode = false` prüfen, dass Pro unverändert ist.

**Vorbedingungen:** Backend läuft bereits auf `:9090` (nicht neu starten). Arbeit auf eigenem Feature-Branch (NICHT `chore/green-maintenance-todos`) — idealerweise im Worktree (`superpowers:using-git-worktrees`). Dev-Server für manuelle Checks: `cd ai-automation-frontend && npm run dev` (Port 3000).

---

### Task 1: i18n-Strings (Fundament)

Neue Schlüssel zuerst, damit alle Folge-Tasks typsicher darauf zugreifen. Beide Dateien müssen `TranslationKeys` (= `DeepStringify<typeof en>`) erfüllen — also identische Struktur in `en.ts` und `de.ts`.

**Files:**
- Modify: `ai-automation-frontend/src/i18n/en.ts` (nav-Objekt ~Z. 49-73; neues Top-Level-Objekt `simple`)
- Modify: `ai-automation-frontend/src/i18n/de.ts` (gleiche Stellen)

- [ ] **Step 1: nav-Keys in `en.ts` ergänzen**

Im `nav: { ... }`-Objekt (nach `debug:`) hinzufügen:

```typescript
    simpleHome: 'What do you want to do?',
    myWorkflows: 'My Workflows',
    results: 'Results',
```

- [ ] **Step 2: Neues `simple`-Objekt in `en.ts` ergänzen**

Als neues Top-Level-Feld im exportierten `en`-Objekt (z. B. direkt vor `settings:`) einfügen:

```typescript
  simple: {
    home: {
      greeting: 'Hi 👋',
      question: 'What do you want to do?',
      inputPlaceholder: 'e.g. "find new jobs in Zurich" or "math learning package grade 7"',
      frequentlyUsed: 'FREQUENTLY USED',
      matchesTitle: 'Matching workflows',
      noMatch: 'None of your workflows matched — show all',
      todayPrefix: 'Today:',
      green: 'ok',
      failed: 'failed',
      viewResults: 'View results',
    },
    workflows: {
      pinned: 'Pinned',
      showAll: 'Show all',
      showLess: 'Show less',
    },
    runs: {
      title: 'Results',
      subtitle: 'Your latest runs — failures first',
      needsAttention: 'Needs attention',
      retry: 'Try again',
      empty: 'No runs yet',
      emptyHint: 'Start a workflow from "My Workflows".',
    },
    onboarding: {
      title: "You're in Simple Mode",
      body: 'Describe what you want and run your workflows. Switch to Pro anytime via the sidebar to build and configure.',
      gotIt: 'Got it',
    },
  },
```

- [ ] **Step 3: Gleiche nav-Keys in `de.ts` ergänzen**

```typescript
    simpleHome: 'Was möchtest du tun?',
    myWorkflows: 'Meine Workflows',
    results: 'Ergebnisse',
```

- [ ] **Step 4: Gleiches `simple`-Objekt in `de.ts` ergänzen (gleiche Struktur, deutsche Werte)**

```typescript
  simple: {
    home: {
      greeting: 'Hallo 👋',
      question: 'Was möchtest du tun?',
      inputPlaceholder: 'z. B. „neue Jobs in Zürich finden" oder „Lernpaket Mathe Klasse 7"',
      frequentlyUsed: 'HÄUFIG GENUTZT',
      matchesTitle: 'Passende Workflows',
      noMatch: 'Keiner deiner Workflows passt genau — alle anzeigen',
      todayPrefix: 'Heute:',
      green: 'grün',
      failed: 'fehlgeschlagen',
      viewResults: 'Ergebnisse ansehen',
    },
    workflows: {
      pinned: 'Angepinnt',
      showAll: 'Alle anzeigen',
      showLess: 'Weniger anzeigen',
    },
    runs: {
      title: 'Ergebnisse',
      subtitle: 'Deine letzten Läufe — Fehler zuerst',
      needsAttention: 'Braucht dich',
      retry: 'Nochmal versuchen',
      empty: 'Noch keine Läufe',
      emptyHint: 'Starte einen Workflow unter „Meine Workflows".',
    },
    onboarding: {
      title: 'Du bist im Simple-Modus',
      body: 'Beschreibe, was du tun willst, und führe deine Workflows aus. Über die Seitenleiste kommst du jederzeit in den Pro-Modus zum Bauen und Konfigurieren.',
      gotIt: 'Verstanden',
    },
  },
```

- [ ] **Step 5: Typecheck**

Run: `cd ai-automation-frontend && npx tsc --noEmit`
Expected: PASS (keine Fehler — `en.ts` und `de.ts` strukturgleich).

- [ ] **Step 6: Commit**

```bash
git add ai-automation-frontend/src/i18n/en.ts ai-automation-frontend/src/i18n/de.ts
git commit -m "i18n(simple): Strings für geführten Simple-Mode (nav + simple.*)"
```

---

### Task 2: settingsStore — Pins + Onboarding-Flag (additiv)

**Files:**
- Modify: `ai-automation-frontend/src/stores/settingsStore.ts`

- [ ] **Step 1: Felder in `SettingsState`-Interface ergänzen**

Nach `sidebarHiddenItems: string[];` einfügen:

```typescript
  pinnedWorkflowIds: string[];
  simpleOnboardingSeen: boolean;
```

Und bei den Settern nach `setSidebarHiddenItems: (hidden: string[]) => void;`:

```typescript
  togglePinnedWorkflow: (id: string) => void;
  setSimpleOnboardingSeen: (seen: boolean) => void;
```

- [ ] **Step 2: Initialwerte + Setter in `create`-Callback ergänzen**

Nach `sidebarHiddenItems: [],`:

```typescript
      pinnedWorkflowIds: [],
      simpleOnboardingSeen: false,
```

Nach `setSidebarHiddenItems: (sidebarHiddenItems) => set({ sidebarHiddenItems }),`:

```typescript
      togglePinnedWorkflow: (id) =>
        set((state) => ({
          pinnedWorkflowIds: state.pinnedWorkflowIds.includes(id)
            ? state.pinnedWorkflowIds.filter((x) => x !== id)
            : [...state.pinnedWorkflowIds, id],
        })),
      setSimpleOnboardingSeen: (simpleOnboardingSeen) => set({ simpleOnboardingSeen }),
```

- [ ] **Step 3: Typecheck**

Run: `cd ai-automation-frontend && npx tsc --noEmit`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add ai-automation-frontend/src/stores/settingsStore.ts
git commit -m "feat(simple): Store um pinnedWorkflowIds + simpleOnboardingSeen erweitern"
```

---

### Task 3: `matchWorkflows`-Util (TDD)

Reine Funktion: Intent-Text → gerankte Workflow-Treffer. Kern von Verhalten A.

**Files:**
- Create: `ai-automation-frontend/src/utils/matchWorkflows.ts`
- Test: `ai-automation-frontend/src/utils/matchWorkflows.test.ts`

- [ ] **Step 1: Failing test schreiben**

```typescript
import { describe, it, expect } from 'vitest';
import { matchWorkflows } from './matchWorkflows';
import type { Workflow } from '../types';

function wf(partial: Partial<Workflow>): Workflow {
  return {
    id: 'id', projectId: 'p', projectName: 'P', name: '', description: '',
    icon: '⚡', blocks: [], activatedSkillIds: [], activatedSkillNames: [],
    version: '1', runCount: 0, instructions: null, model: null, provider: null,
    createdAt: '', updatedAt: '', tags: [], ...partial,
  } as Workflow;
}

describe('matchWorkflows', () => {
  const workflows = [
    wf({ id: 'job', name: 'Job Shortlist Generator', description: 'finds jobs', tags: ['jobs'], runCount: 69 }),
    wf({ id: 'radar', name: 'Lebens-Radar', description: 'weekly life radar', tags: ['radar'], runCount: 51 }),
    wf({ id: 'lern', name: 'Lernpaket Generator', description: 'learning packages', tags: ['learning'], runCount: 227 }),
  ];

  it('returns [] for empty intent', () => {
    expect(matchWorkflows('   ', workflows)).toEqual([]);
  });

  it('matches by name token', () => {
    const r = matchWorkflows('job', workflows);
    expect(r[0].workflow.id).toBe('job');
  });

  it('matches by tag', () => {
    const r = matchWorkflows('radar', workflows);
    expect(r.map((m) => m.workflow.id)).toContain('radar');
  });

  it('filters out non-matches', () => {
    const r = matchWorkflows('xyzzy', workflows);
    expect(r).toEqual([]);
  });

  it('breaks ties by runCount (more-used first)', () => {
    const tie = [wf({ id: 'a', name: 'report alpha', runCount: 5 }), wf({ id: 'b', name: 'report beta', runCount: 50 })];
    const r = matchWorkflows('report', tie);
    expect(r[0].workflow.id).toBe('b');
  });
});
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `cd ai-automation-frontend && npx vitest run src/utils/matchWorkflows.test.ts`
Expected: FAIL ("Failed to resolve import './matchWorkflows'").

- [ ] **Step 3: Implementierung schreiben**

```typescript
import type { Workflow } from '../types';

export interface WorkflowMatch {
  workflow: Workflow;
  score: number;
}

/**
 * Ranks workflows against a free-text intent. Pure function, no I/O.
 * Score = token hits in name/description/tags (+ extra weight for name hits),
 * with runCount as a small tie-breaker. Non-matches (score 0) are dropped.
 */
export function matchWorkflows(intent: string, workflows: Workflow[]): WorkflowMatch[] {
  const q = intent.trim().toLowerCase();
  if (!q) return [];
  const tokens = q.split(/\s+/).filter(Boolean);

  const scored = workflows.map((w) => {
    const name = w.name.toLowerCase();
    const haystack = [w.name, w.description ?? '', ...(w.tags ?? [])].join(' ').toLowerCase();
    let score = 0;
    for (const tok of tokens) {
      if (haystack.includes(tok)) score += 1;
      if (name.includes(tok)) score += 1; // name hits weigh double
    }
    if (score > 0) score += Math.min(w.runCount, 1000) / 10000; // tie-breaker
    return { workflow: w, score };
  });

  return scored
    .filter((m) => m.score > 0)
    .sort((a, b) => b.score - a.score);
}
```

- [ ] **Step 4: Test laufen lassen, grün bestätigen**

Run: `cd ai-automation-frontend && npx vitest run src/utils/matchWorkflows.test.ts`
Expected: PASS (5 Tests).

- [ ] **Step 5: Commit**

```bash
git add ai-automation-frontend/src/utils/matchWorkflows.ts ai-automation-frontend/src/utils/matchWorkflows.test.ts
git commit -m "feat(simple): matchWorkflows-Util (Intent→Workflow-Ranking) + Tests"
```

---

### Task 4: `curateWorkflows`-Util (TDD) — Masse/Ausnahme

**Files:**
- Create: `ai-automation-frontend/src/utils/curateWorkflows.ts`
- Test: `ai-automation-frontend/src/utils/curateWorkflows.test.ts`

- [ ] **Step 1: Failing test schreiben**

```typescript
import { describe, it, expect } from 'vitest';
import { curateWorkflows } from './curateWorkflows';
import type { Workflow } from '../types';

function wf(id: string, runCount: number, name = id): Workflow {
  return {
    id, projectId: 'p', projectName: 'P', name, description: '', icon: '⚡',
    blocks: [], activatedSkillIds: [], activatedSkillNames: [], version: '1',
    runCount, instructions: null, model: null, provider: null,
    createdAt: '', updatedAt: '', tags: [],
  } as Workflow;
}

describe('curateWorkflows', () => {
  const all = [wf('a', 5), wf('b', 100), wf('c', 50), wf('d', 0), wf('e', 200), wf('f', 10), wf('g', 1)];

  it('fills curated with top-by-runCount up to limit', () => {
    const { curated } = curateWorkflows(all, [], 3);
    expect(curated.map((w) => w.id)).toEqual(['e', 'b', 'c']);
  });

  it('always includes pinned, even beyond runCount ordering', () => {
    const { curated } = curateWorkflows(all, ['d'], 3);
    expect(curated.map((w) => w.id)).toEqual(['d', 'e', 'b']);
  });

  it('rest excludes everything curated', () => {
    const { curated, rest } = curateWorkflows(all, ['d'], 3);
    const curatedIds = new Set(curated.map((w) => w.id));
    expect(rest.every((w) => !curatedIds.has(w.id))).toBe(true);
    expect(curated.length + rest.length).toBe(all.length);
  });

  it('keeps all pinned even if pinned count exceeds limit', () => {
    const { curated } = curateWorkflows(all, ['a', 'f', 'g', 'd'], 2);
    expect(curated.map((w) => w.id).sort()).toEqual(['a', 'd', 'f', 'g']);
  });
});
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `cd ai-automation-frontend && npx vitest run src/utils/curateWorkflows.test.ts`
Expected: FAIL (import nicht auflösbar).

- [ ] **Step 3: Implementierung schreiben**

```typescript
import type { Workflow } from '../types';

export interface CuratedWorkflows {
  curated: Workflow[];
  rest: Workflow[];
}

/**
 * "Masse und Ausnahme separieren": curated = all pinned ∪ top-by-runCount up to `limit`.
 * Pure function. `rest` (sorted by name) holds everything not in curated.
 */
export function curateWorkflows(
  workflows: Workflow[],
  pinnedIds: string[],
  limit = 6,
): CuratedWorkflows {
  const pinnedSet = new Set(pinnedIds);
  const pinned = workflows.filter((w) => pinnedSet.has(w.id));
  const unpinnedByRuns = workflows
    .filter((w) => !pinnedSet.has(w.id))
    .sort((a, b) => b.runCount - a.runCount);

  const fillCount = Math.max(0, limit - pinned.length);
  const curated = [...pinned, ...unpinnedByRuns.slice(0, fillCount)];
  const rest = unpinnedByRuns
    .slice(fillCount)
    .sort((a, b) => a.name.localeCompare(b.name));

  return { curated, rest };
}
```

- [ ] **Step 4: Test laufen lassen, grün bestätigen**

Run: `cd ai-automation-frontend && npx vitest run src/utils/curateWorkflows.test.ts`
Expected: PASS (4 Tests).

- [ ] **Step 5: Commit**

```bash
git add ai-automation-frontend/src/utils/curateWorkflows.ts ai-automation-frontend/src/utils/curateWorkflows.test.ts
git commit -m "feat(simple): curateWorkflows-Util (Masse/Ausnahme) + Tests"
```

---

### Task 5: `latestRunStatusMap`-Util (TDD) + Hook

Letzter-Lauf-Status pro Workflow aus einer Run-Liste — für Status-Punkte und den Heute-Streifen. Eine Query, kein N+1.

**Files:**
- Create: `ai-automation-frontend/src/utils/latestRunStatus.ts`
- Test: `ai-automation-frontend/src/utils/latestRunStatus.test.ts`
- Create: `ai-automation-frontend/src/hooks/useLatestRunStatuses.ts`

- [ ] **Step 1: Failing test schreiben**

```typescript
import { describe, it, expect } from 'vitest';
import { latestRunStatusMap } from './latestRunStatus';
import type { WorkflowRun } from '../types';

function run(workflowId: string, status: WorkflowRun['status'], startedAt: string): WorkflowRun {
  return {
    id: `${workflowId}-${startedAt}`, workflowId, workflowName: workflowId,
    projectName: null, projectPath: null, status, steps: [],
    startedAt, completedAt: null, durationMs: null, userRating: null,
    error: null, metadata: {},
  } as WorkflowRun;
}

describe('latestRunStatusMap', () => {
  it('keeps the most recent run status per workflow', () => {
    const runs = [
      run('w1', 'FAILED', '2026-06-15T08:00:00Z'),
      run('w1', 'COMPLETED', '2026-06-15T10:00:00Z'),
      run('w2', 'RUNNING', '2026-06-15T09:00:00Z'),
    ];
    const m = latestRunStatusMap(runs);
    expect(m.get('w1')).toBe('COMPLETED');
    expect(m.get('w2')).toBe('RUNNING');
  });

  it('returns empty map for no runs', () => {
    expect(latestRunStatusMap([]).size).toBe(0);
  });
});
```

- [ ] **Step 2: Test laufen lassen, Fehlschlag bestätigen**

Run: `cd ai-automation-frontend && npx vitest run src/utils/latestRunStatus.test.ts`
Expected: FAIL.

- [ ] **Step 3: Implementierung schreiben**

```typescript
import type { WorkflowRun, RunStatus } from '../types';

/** Most-recent (by startedAt) run status per workflowId. Pure function. */
export function latestRunStatusMap(runs: WorkflowRun[]): Map<string, RunStatus> {
  const latest = new Map<string, { status: RunStatus; startedAt: string }>();
  for (const r of runs) {
    const prev = latest.get(r.workflowId);
    if (!prev || r.startedAt > prev.startedAt) {
      latest.set(r.workflowId, { status: r.status, startedAt: r.startedAt });
    }
  }
  const out = new Map<string, RunStatus>();
  for (const [id, v] of latest) out.set(id, v.status);
  return out;
}
```

- [ ] **Step 4: Test laufen lassen, grün bestätigen**

Run: `cd ai-automation-frontend && npx vitest run src/utils/latestRunStatus.test.ts`
Expected: PASS (2 Tests).

- [ ] **Step 5: Hook schreiben (Wiring, kein Test)**

```typescript
import { useMemo } from 'react';
import { useAllRuns } from './useWorkflows';
import { latestRunStatusMap } from '../utils/latestRunStatus';

/** Map workflowId → most recent run status, from the most recent `size` runs. */
export function useLatestRunStatuses(size = 100) {
  const { data } = useAllRuns({ size });
  return useMemo(() => latestRunStatusMap(data?.content ?? []), [data]);
}
```

- [ ] **Step 6: Typecheck + Commit**

Run: `cd ai-automation-frontend && npx tsc --noEmit`
Expected: PASS.

```bash
git add ai-automation-frontend/src/utils/latestRunStatus.ts ai-automation-frontend/src/utils/latestRunStatus.test.ts ai-automation-frontend/src/hooks/useLatestRunStatuses.ts
git commit -m "feat(simple): latestRunStatusMap-Util + useLatestRunStatuses-Hook"
```

---

### Task 6: `SimpleHome`-Seite (geführter Einstieg)

**Files:**
- Create: `ai-automation-frontend/src/pages/SimpleHome.tsx`

- [ ] **Step 1: Seite schreiben**

```tsx
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Play } from 'lucide-react';
import { useT } from '../i18n';
import { useWorkflows, useExecuteWorkflow, useAllRuns } from '../hooks/useWorkflows';
import { useSettingsStore } from '../stores/settingsStore';
import { matchWorkflows } from '../utils/matchWorkflows';
import { curateWorkflows } from '../utils/curateWorkflows';
import { extractWorkflowVariables } from '../utils/extractWorkflowVariables';
import { readContextSchema } from '../utils/contextSchema';
import { ExecuteDialog } from '../components/workflow/ExecuteDialog';
import { Spinner, Button } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { SimpleOnboarding } from '../components/simple/SimpleOnboarding';
import type { Workflow } from '../types';

const TERMINAL_OK = new Set(['COMPLETED', 'COMPLETED_WITH_ERRORS']);

export function SimpleHome() {
  const t = useT();
  const navigate = useNavigate();
  const { pinnedWorkflowIds } = useSettingsStore();
  const { data: workflows, isLoading, error, refetch } = useWorkflows();
  const { data: runsPage } = useAllRuns({ size: 100 });
  const executeWorkflow = useExecuteWorkflow();

  const [intent, setIntent] = useState('');
  const [executeDialog, setExecuteDialog] = useState<{ open: boolean; workflow: Workflow | null; variables: string[] }>(
    { open: false, workflow: null, variables: [] },
  );

  const matches = useMemo(() => matchWorkflows(intent, workflows ?? []).slice(0, 5), [intent, workflows]);
  const chips = useMemo(() => curateWorkflows(workflows ?? [], pinnedWorkflowIds, 4).curated, [workflows, pinnedWorkflowIds]);

  const today = useMemo(() => {
    const runs = runsPage?.content ?? [];
    const todayStr = new Date().toISOString().slice(0, 10);
    const todays = runs.filter((r) => r.startedAt?.slice(0, 10) === todayStr);
    return {
      green: todays.filter((r) => TERMINAL_OK.has(r.status)).length,
      failed: todays.filter((r) => r.status === 'FAILED').length,
    };
  }, [runsPage]);

  const handleExecute = (workflow: Workflow) => {
    const variables = extractWorkflowVariables(workflow.blocks);
    if (variables.length > 0) {
      setExecuteDialog({ open: true, workflow, variables });
    } else {
      executeWorkflow.mutate({ id: workflow.id }, { onSuccess: (run) => navigate(`/workflows/${workflow.id}/run/${run.id}`) });
    }
  };

  const handleExecuteWithContext = (context: Record<string, string>) => {
    if (!executeDialog.workflow) return;
    const id = executeDialog.workflow.id;
    executeWorkflow.mutate(
      { id, context },
      {
        onSuccess: (run) => {
          setExecuteDialog({ open: false, workflow: null, variables: [] });
          navigate(`/workflows/${id}/run/${run.id}`);
        },
      },
    );
  };

  if (isLoading) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }
  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <div className="animate-fade-in max-w-3xl mx-auto">
      <SimpleOnboarding />

      <p className="text-sm text-text-secondary mb-1">{t.simple.home.greeting}</p>
      <h1 className="text-2xl font-bold text-text mb-6">{t.simple.home.question}</h1>

      <input
        type="text"
        value={intent}
        onChange={(e) => setIntent(e.target.value)}
        placeholder={t.simple.home.inputPlaceholder}
        className="w-full text-sm bg-surface border border-border rounded-xl px-4 py-3 text-text focus:outline-none focus:ring-1 focus:ring-accent mb-4"
      />

      {intent.trim() && matches.length > 0 && (
        <div className="space-y-2 mb-6">
          <p className="text-xs text-text-tertiary">{t.simple.home.matchesTitle}</p>
          {matches.map((m) => (
            <div
              key={m.workflow.id}
              className="flex items-center justify-between px-4 py-3 rounded-lg bg-surface border border-border hover:border-accent/40 transition-colors cursor-pointer"
              onClick={() => navigate(`/workflows/${m.workflow.id}`)}
            >
              <div className="flex items-center gap-3 min-w-0">
                <span className="text-lg">{m.workflow.icon || '⚡'}</span>
                <p className="text-sm font-medium text-text truncate">{m.workflow.name}</p>
              </div>
              <Button size="sm" variant="secondary" icon={<Play className="w-3.5 h-3.5" />}
                onClick={(e) => { e.stopPropagation(); handleExecute(m.workflow); }} loading={executeWorkflow.isPending}>
                {t.workflows.execute}
              </Button>
            </div>
          ))}
        </div>
      )}

      {intent.trim() && matches.length === 0 && (
        <button className="text-sm text-accent hover:underline mb-6" onClick={() => navigate('/workflows')}>
          {t.simple.home.noMatch}
        </button>
      )}

      {!intent.trim() && chips.length > 0 && (
        <>
          <p className="text-xs text-text-tertiary mb-2">{t.simple.home.frequentlyUsed}</p>
          <div className="flex gap-2 flex-wrap mb-8">
            {chips.map((w) => (
              <button key={w.id}
                className="flex items-center gap-2 bg-surface border border-border rounded-full px-3.5 py-2 text-sm text-text-secondary hover:border-accent/40 transition-colors"
                onClick={() => handleExecute(w)}>
                <span>{w.icon || '⚡'}</span>{w.name}
              </button>
            ))}
          </div>
        </>
      )}

      <div className="flex items-center justify-between bg-surface border border-border rounded-lg px-4 py-3 cursor-pointer hover:border-accent/40 transition-colors"
        onClick={() => navigate('/runs')}>
        <span className="text-sm text-text-secondary">
          {t.simple.home.todayPrefix}{' '}
          <span className="text-success font-medium">{today.green} {t.simple.home.green}</span>
          {' · '}
          <span className="text-danger font-medium">{today.failed} {t.simple.home.failed}</span>
        </span>
        <span className="text-sm text-accent">{t.simple.home.viewResults} →</span>
      </div>

      <ExecuteDialog
        open={executeDialog.open}
        onClose={() => setExecuteDialog({ open: false, workflow: null, variables: [] })}
        onExecute={handleExecuteWithContext}
        variables={executeDialog.variables}
        defaultValues={executeDialog.workflow?.defaultContext}
        optionalVariables={executeDialog.workflow ? readContextSchema(executeDialog.workflow).optional : undefined}
        descriptions={executeDialog.workflow ? readContextSchema(executeDialog.workflow).descriptions : undefined}
        placeholders={executeDialog.workflow ? readContextSchema(executeDialog.workflow).placeholders : undefined}
        loading={executeWorkflow.isPending}
      />
    </div>
  );
}
```

> Hinweis: `SimpleOnboarding` wird in Task 11 erstellt. Reihenfolge bei Subagent-Ausführung: Task 11 vor Task 6 ausführen ODER den `SimpleOnboarding`-Import + die Zeile `<SimpleOnboarding />` zunächst auskommentiert lassen und in Task 11 aktivieren. Empfehlung: **Task 11 vor Task 6**.

- [ ] **Step 2: Typecheck + Lint**

Run: `cd ai-automation-frontend && npx tsc --noEmit && npm run lint`
Expected: PASS (sofern Task 11 erledigt; sonst fehlt nur `SimpleOnboarding`).

- [ ] **Step 3: Commit**

```bash
git add ai-automation-frontend/src/pages/SimpleHome.tsx
git commit -m "feat(simple): SimpleHome — geführter Einstieg (Intent-Match + Chips + Heute-Streifen)"
```

---

### Task 7: SimpleHome ins Routing einhängen (App.tsx)

**Files:**
- Modify: `ai-automation-frontend/src/App.tsx` (Lazy-Imports oben; `DashboardRoute` ~Z. 20-23)

- [ ] **Step 1: Lazy-Import ergänzen, SimpleDashboard-Import entfernen**

Entferne die Zeile:
```typescript
const SimpleDashboard = lazy(() => import('./pages/SimpleDashboard').then(m => ({ default: m.SimpleDashboard })));
```
Füge stattdessen hinzu:
```typescript
const SimpleHome = lazy(() => import('./pages/SimpleHome').then(m => ({ default: m.SimpleHome })));
```

- [ ] **Step 2: `DashboardRoute` auf SimpleHome umstellen**

```typescript
function DashboardRoute() {
  const { simpleMode } = useSettingsStore();
  return simpleMode ? <SimpleHome /> : <Dashboard />;
}
```

- [ ] **Step 3: Typecheck + Lint**

Run: `cd ai-automation-frontend && npx tsc --noEmit && npm run lint`
Expected: PASS (kein „SimpleDashboard is declared but never read").

- [ ] **Step 4: Manuelle Browser-Verifikation**

Dev-Server: `cd ai-automation-frontend && npm run dev` → `http://localhost:3000`. Settings → Simple Mode AN. Auf `/` erscheint „Was möchtest du tun?" mit Eingabefeld + Chips. Tippe „job" → passende Workflows mit ▶. Pro Mode → `/` zeigt unverändert das alte Dashboard.

- [ ] **Step 5: Commit**

```bash
git add ai-automation-frontend/src/App.tsx
git commit -m "feat(simple): / im Simple-Mode auf SimpleHome routen (SimpleDashboard ungeroutet)"
```

---

### Task 8: SimpleWorkflows — kuratiert + Status-Punkt + Pin + „Alle anzeigen"

**Files:**
- Modify: `ai-automation-frontend/src/pages/SimpleWorkflows.tsx`

> Vor dem Editieren die Datei lesen, um die exakten Anker zu treffen.

- [ ] **Step 1: Imports ergänzen**

Hinzufügen:
```typescript
import { Pin } from 'lucide-react';
import { curateWorkflows } from '../utils/curateWorkflows';
import { useLatestRunStatuses } from '../hooks/useLatestRunStatuses';
import { StatusBadge } from '../components/shared/StatusBadge';
```

- [ ] **Step 2: State + abgeleitete Daten**

Im Komponenten-Body, nach den bestehenden Hooks, ergänzen:
```typescript
  const { pinnedWorkflowIds, togglePinnedWorkflow } = useSettingsStore();
  const latestStatus = useLatestRunStatuses();
  const [showAll, setShowAll] = useState(false);
```
(`useSettingsStore` ggf. importieren, falls noch nicht vorhanden.)

Ersetze die Such-/Sortierlogik (`const sorted = useMemo(... )`) durch:
```typescript
  const filtered = useMemo(() => {
    const base = [...(workflows ?? [])];
    if (!search) return base;
    const q = search.toLowerCase();
    return base.filter(
      (w) => w.name.toLowerCase().includes(q) || (w.description?.toLowerCase().includes(q) ?? false),
    );
  }, [workflows, search]);

  const { curated, rest } = useMemo(
    () => curateWorkflows(filtered, pinnedWorkflowIds, 6),
    [filtered, pinnedWorkflowIds],
  );

  // Bei aktiver Suche: alle Treffer flach; sonst: kuratiert (+ optional Rest).
  const visible = search ? [...curated, ...rest] : showAll ? [...curated, ...rest] : curated;
```

- [ ] **Step 3: Karten-Render erweitern (Status-Punkt + Pin)**

Ersetze `{sorted.map((workflow) => ( ... ))}` durch `{visible.map((workflow) => ( ... ))}` und erweitere die Karte (rechte Seite) um Status-Punkt + Pin neben dem Execute-Button:

```tsx
{visible.map((workflow) => (
  <div
    key={workflow.id}
    className="flex items-center justify-between px-4 py-3 rounded-lg bg-surface border border-border hover:border-accent/40 transition-colors cursor-pointer"
    onClick={() => navigate(`/workflows/${workflow.id}`)}
  >
    <div className="flex items-center gap-3 min-w-0">
      <span className="text-lg">{workflow.icon || '⚡'}</span>
      <div className="min-w-0">
        <p className="text-sm font-medium text-text truncate">{workflow.name}</p>
        {workflow.description && (
          <p className="text-xs text-text-tertiary truncate">{workflow.description}</p>
        )}
      </div>
    </div>
    <div className="flex items-center gap-2 shrink-0">
      {latestStatus.get(workflow.id) && <StatusBadge status={latestStatus.get(workflow.id)!} />}
      <button
        aria-label="pin"
        className={pinnedWorkflowIds.includes(workflow.id) ? 'text-accent' : 'text-text-tertiary hover:text-text'}
        onClick={(e) => { e.stopPropagation(); togglePinnedWorkflow(workflow.id); }}
      >
        <Pin className="w-3.5 h-3.5" />
      </button>
      <Button
        size="sm"
        variant="secondary"
        icon={<Play className="w-3.5 h-3.5" />}
        onClick={(e) => { e.stopPropagation(); handleExecute(workflow); }}
        loading={executeWorkflow.isPending}
      >
        {t.workflows.execute}
      </Button>
    </div>
  </div>
))}
```

- [ ] **Step 4: „Alle anzeigen"-Umschalter (nur ohne Suche, wenn Rest existiert)**

Direkt nach der Liste einfügen:
```tsx
{!search && rest.length > 0 && (
  <button
    className="text-sm text-accent hover:underline mt-3"
    onClick={() => setShowAll((v) => !v)}
  >
    {showAll ? t.simple.workflows.showLess : `${t.simple.workflows.showAll} (${rest.length})`}
  </button>
)}
```

- [ ] **Step 5: Typecheck + Lint**

Run: `cd ai-automation-frontend && npx tsc --noEmit && npm run lint`
Expected: PASS.

- [ ] **Step 6: Manuelle Browser-Verifikation**

Simple Mode → `/workflows`: nur kuratierte Handvoll sichtbar, Status-Punkt am letzten Lauf, Pin togglet (bleibt nach Reload via localStorage), „Alle anzeigen (N)" klappt Rest auf. Suche filtert flach. Execute-Dialog wie gehabt.

- [ ] **Step 7: Commit**

```bash
git add ai-automation-frontend/src/pages/SimpleWorkflows.tsx
git commit -m "feat(simple): Meine Workflows — kuratiert (Masse/Ausnahme), Status-Punkt, Pin, Alle anzeigen"
```

---

### Task 9: SimpleRuns-Seite (Ergebnisse, Fehler zuerst) + Routing

**Files:**
- Create: `ai-automation-frontend/src/pages/SimpleRuns.tsx`
- Modify: `ai-automation-frontend/src/App.tsx` (RunsRoute-Wrapper + Route)

- [ ] **Step 1: SimpleRuns schreiben**

```tsx
import { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { RotateCcw } from 'lucide-react';
import { useT } from '../i18n';
import { useAllRuns, useExecuteWorkflow } from '../hooks/useWorkflows';
import { StatusBadge } from '../components/shared/StatusBadge';
import { Spinner, Button, Card } from '../components/ui';
import { ErrorState } from '../components/shared/ErrorState';
import { EmptyState } from '../components/shared/EmptyState';
import { formatDateTime, formatDuration } from '../utils/formatTime';
import type { WorkflowRun } from '../types';

export function SimpleRuns() {
  const t = useT();
  const navigate = useNavigate();
  const { data: runsPage, isLoading, error, refetch } = useAllRuns({ size: 50 });
  const executeWorkflow = useExecuteWorkflow();

  const ordered = useMemo(() => {
    const runs = [...(runsPage?.content ?? [])];
    // Fehler zuerst, dann nach Startzeit absteigend.
    return runs.sort((a, b) => {
      const af = a.status === 'FAILED' ? 0 : 1;
      const bf = b.status === 'FAILED' ? 0 : 1;
      if (af !== bf) return af - bf;
      return (b.startedAt ?? '').localeCompare(a.startedAt ?? '');
    });
  }, [runsPage]);

  if (isLoading) {
    return <div className="flex items-center justify-center h-64"><Spinner text={t.common.loading} /></div>;
  }
  if (error) {
    return <ErrorState message={error.message} onRetry={() => refetch()} />;
  }

  return (
    <div className="animate-fade-in max-w-3xl mx-auto">
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-text">{t.simple.runs.title}</h1>
        <p className="text-sm text-text-secondary mt-1">{t.simple.runs.subtitle}</p>
      </div>

      {ordered.length === 0 ? (
        <EmptyState title={t.simple.runs.empty} description={t.simple.runs.emptyHint} />
      ) : (
        <div className="space-y-2">
          {ordered.map((run: WorkflowRun) => {
            const isFailed = run.status === 'FAILED';
            return (
              <Card key={run.id}>
                <div className="flex items-center justify-between gap-3">
                  <div
                    className="flex items-center gap-3 min-w-0 cursor-pointer"
                    onClick={() => navigate(`/workflows/${run.workflowId}/run/${run.id}`)}
                  >
                    <StatusBadge status={run.status} />
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-text truncate">{run.workflowName}</p>
                      <p className="text-xs text-text-tertiary truncate">
                        {run.startedAt ? formatDateTime(run.startedAt) : '-'} · {formatDuration(run.durationMs)}
                        {isFailed && run.error?.message ? ` · ${run.error.message}` : ''}
                      </p>
                    </div>
                  </div>
                  {isFailed && (
                    <Button
                      size="sm"
                      variant="secondary"
                      icon={<RotateCcw className="w-3.5 h-3.5" />}
                      loading={executeWorkflow.isPending}
                      onClick={() =>
                        executeWorkflow.mutate(
                          { id: run.workflowId },
                          { onSuccess: (r) => navigate(`/workflows/${run.workflowId}/run/${r.id}`) },
                        )
                      }
                    >
                      {t.simple.runs.retry}
                    </Button>
                  )}
                </div>
              </Card>
            );
          })}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: RunsRoute-Wrapper + Lazy-Import in App.tsx**

Lazy-Import oben ergänzen:
```typescript
const SimpleRuns = lazy(() => import('./pages/SimpleRuns').then(m => ({ default: m.SimpleRuns })));
```
Neuen Wrapper bei den anderen Route-Funktionen ergänzen:
```typescript
function RunsRoute() {
  const { simpleMode } = useSettingsStore();
  return simpleMode ? <SimpleRuns /> : <Runs />;
}
```
Route-Definition `{ path: 'runs', element: <Runs /> }` ersetzen durch:
```typescript
{ path: 'runs', element: <RunsRoute /> },
```

- [ ] **Step 3: Typecheck + Lint**

Run: `cd ai-automation-frontend && npx tsc --noEmit && npm run lint`
Expected: PASS.

- [ ] **Step 4: Manuelle Browser-Verifikation**

Simple Mode → `/runs`: fehlgeschlagene Läufe oben, Klartext-Zeile (Zeit · Dauer · Fehlermeldung), „Nochmal versuchen" bei Fehlern startet den Workflow neu. Pro Mode → `/runs` zeigt unverändert die volle `Runs`-Tabelle.

- [ ] **Step 5: Commit**

```bash
git add ai-automation-frontend/src/pages/SimpleRuns.tsx ai-automation-frontend/src/App.tsx
git commit -m "feat(simple): Ergebnisse-Seite (Fehler zuerst, Klartext, Nochmal versuchen) + Routing"
```

---

### Task 10: Sidebar — Simple-Labels überschreiben

**Files:**
- Modify: `ai-automation-frontend/src/components/layout/Sidebar.tsx` (`navItemsWithLabels` ~Z. 142-145)

- [ ] **Step 1: Label-Override für Simple-Routen**

Ersetze die `navItemsWithLabels`-Erzeugung durch:
```typescript
const SIMPLE_LABEL_KEYS: Record<string, 'simpleHome' | 'myWorkflows' | 'results'> = {
  '/': 'simpleHome',
  '/workflows': 'myWorkflows',
  '/runs': 'results',
};

const navItemsWithLabels = navItems.map((item) => ({
  ...item,
  label:
    simpleMode && SIMPLE_LABEL_KEYS[item.to]
      ? t.nav[SIMPLE_LABEL_KEYS[item.to]]
      : t.nav[item.labelKey],
}));
```
(`simpleMode` ist in der Komponente bereits vorhanden — wird für den Filter `visibleNavItems` genutzt.)

- [ ] **Step 2: Typecheck + Lint**

Run: `cd ai-automation-frontend && npx tsc --noEmit && npm run lint`
Expected: PASS.

- [ ] **Step 3: Manuelle Browser-Verifikation**

Simple Mode → Sidebar zeigt genau drei Punkte: „Was möchtest du tun?", „Meine Workflows", „Ergebnisse". Pro Mode → alle Labels unverändert (Dashboard, Workflows, Runs, …).

- [ ] **Step 4: Commit**

```bash
git add ai-automation-frontend/src/components/layout/Sidebar.tsx
git commit -m "feat(simple): Sidebar-Labels im Simple-Mode (Was möchtest du tun? / Meine Workflows / Ergebnisse)"
```

---

### Task 11: SimpleOnboarding + Settings verschlanken

**Files:**
- Create: `ai-automation-frontend/src/components/simple/SimpleOnboarding.tsx`
- Modify: `ai-automation-frontend/src/components/settings/GeneralSettingsCard.tsx`

> Diese Task vor Task 6 ausführen (SimpleHome importiert `SimpleOnboarding`).

- [ ] **Step 1: SimpleOnboarding schreiben**

```tsx
import { X } from 'lucide-react';
import { useT } from '../../i18n';
import { useSettingsStore } from '../../stores/settingsStore';

/** One-time intro banner shown on the Simple home until dismissed. */
export function SimpleOnboarding() {
  const t = useT();
  const { simpleOnboardingSeen, setSimpleOnboardingSeen } = useSettingsStore();
  if (simpleOnboardingSeen) return null;

  return (
    <div className="relative bg-surface border border-accent/30 rounded-xl px-4 py-3 mb-6">
      <button
        aria-label="dismiss"
        className="absolute top-3 right-3 text-text-tertiary hover:text-text"
        onClick={() => setSimpleOnboardingSeen(true)}
      >
        <X className="w-4 h-4" />
      </button>
      <p className="text-sm font-medium text-text">{t.simple.onboarding.title}</p>
      <p className="text-xs text-text-secondary mt-1 pr-6">{t.simple.onboarding.body}</p>
      <button
        className="text-xs text-accent hover:underline mt-2"
        onClick={() => setSimpleOnboardingSeen(true)}
      >
        {t.simple.onboarding.gotIt}
      </button>
    </div>
  );
}
```

- [ ] **Step 2: GeneralSettingsCard — Provider/Modelle/Vorschläge im Simple-Mode ausblenden**

In `GeneralSettingsCard.tsx` die drei Blöcke **Default AI Provider** (Z. 73-92), **Assistant Model** (Z. 94-110) und **Suggestions Enabled** + **Suggestions Model** (Z. 112-147) jeweils in `{!simpleMode && ( ... )}` einwickeln. Beispiel für den Provider-Block:

```tsx
{!simpleMode && (
  <div className="flex items-center justify-between">
    <div className="flex items-center gap-3">
      <Bot className="w-5 h-5 text-text-secondary" />
      <div>
        <p className="text-sm font-medium text-text">{t.settings.defaultAiProvider}</p>
        <p className="text-xs text-text-tertiary">{t.settings.defaultAiProviderHint}</p>
      </div>
    </div>
    <Select
      value={defaultAiProvider || aiProviders?.default || ''}
      onChange={(e) => {
        setDefaultAiProvider(e.target.value);
        updateSetting.mutate({ key: 'default-ai-provider', value: e.target.value });
      }}
      options={(aiProviders?.providers || []).map((p: string) => ({
        value: p,
        label: p === 'CLAUDE' ? 'Claude CLI' : p === 'CODEX' ? 'Codex CLI' : p === 'PI' ? 'pi (Mario Zechner)' : p,
      }))}
    />
  </div>
)}
```
Analog Assistant-Model-Block und beide Suggestions-Blöcke in `{!simpleMode && ( ... )}`. (Der `{suggestionsEnabled && (...)}`-Suggestions-Model-Block wird zusätzlich von der `!simpleMode`-Hülle umschlossen.) So bleibt im Simple-Mode nur **Simple-Mode-Toggle (= Pro-Wechsel) + Sprache**; AppearanceCard (Theme) und SecurityCard (API-Key) liefern den Rest. Pro (`!simpleMode`) ist unverändert.

- [ ] **Step 3: Typecheck + Lint**

Run: `cd ai-automation-frontend && npx tsc --noEmit && npm run lint`
Expected: PASS.

- [ ] **Step 4: Manuelle Browser-Verifikation**

Simple Mode → Settings zeigt nur: Simple-Mode-Toggle, Sprache, Theme, API-Key, System-Info. Erster Besuch von `/` im Simple-Mode zeigt das Onboarding-Banner; nach „Verstanden"/X bleibt es weg (auch nach Reload). Pro Mode → Settings vollständig wie vorher.

- [ ] **Step 5: Commit**

```bash
git add ai-automation-frontend/src/components/simple/SimpleOnboarding.tsx ai-automation-frontend/src/components/settings/GeneralSettingsCard.tsx
git commit -m "feat(simple): Onboarding-Banner + Settings im Simple-Mode auf Theme/Sprache/API-Key/Pro verschlanken"
```

---

### Task 12: Gesamt-Verifikation (Tests grün + Pro-Regression)

**Files:** keine (Verifikation)

- [ ] **Step 1: Alle Unit-Tests**

Run: `cd ai-automation-frontend && npx vitest run`
Expected: PASS (inkl. matchWorkflows, curateWorkflows, latestRunStatus + bestehende Tests).

- [ ] **Step 2: Typecheck + Lint (gesamt)**

Run: `cd ai-automation-frontend && npx tsc --noEmit && npm run lint`
Expected: PASS, keine Warnungen zu ungenutzten Imports (SimpleDashboard).

- [ ] **Step 3: Manuelle End-to-End-Verifikation Simple**

Dev-Server `localhost:3000`, Simple Mode AN:
- Sidebar = 3 Punkte (Was möchtest du tun? / Meine Workflows / Ergebnisse) + Settings.
- `/`: Intent tippen → Treffer + ▶; Chips ohne Intent; Heute-Streifen klickt zu `/runs`; Onboarding nur beim ersten Mal.
- `/workflows`: kuratiert + Status-Punkt + Pin + Alle anzeigen + Suche.
- `/runs`: Fehler zuerst, Klartext, Nochmal versuchen.
- Settings: nur 4 Dinge + System-Info.

- [ ] **Step 4: Pro-Regression**

Simple Mode AUS: `/`, `/workflows`, `/runs`, Settings, Sidebar-Labels — alles unverändert wie vor diesem Branch. (C1 erfüllt.)

- [ ] **Step 5: TODO schließen**

Nach erfolgreicher Verifikation `e8e58c58` via `PATCH /api/v1/todos/e8e58c58-578d-4bff-a80c-b30fa70c11e7/status` auf `DONE` setzen (API-Key aus `app_settings`).

---

## Self-Review (vom Plan-Autor)

**Spec-Abdeckung:** Navigation (Task 7,9,10) · geführter Einstieg A (Task 3,6) · Meine Workflows Masse/Ausnahme (Task 4,8) · Ergebnisse/Wahrnehmen (Task 5,9) · Settings-Verschlankung + Onboarding + Store (Task 1,2,11). Phase-2-Themen (Backend-Guards, semantisches Matching) sind bewusst nicht enthalten (Out-of-Scope laut Spec §10).

**Platzhalter:** keine — jeder Code-Step zeigt vollständigen Code; jeder Test-Step echte Assertions; jeder Run-Step exaktes Kommando + erwartetes Ergebnis.

**Typ-Konsistenz:** `matchWorkflows`→`WorkflowMatch`, `curateWorkflows`→`CuratedWorkflows`, `latestRunStatusMap`→`Map<string, RunStatus>` durchgehend gleich benannt; `Workflow.runCount`/`tags`, `WorkflowRun.status/startedAt/error.message/durationMs/workflowId` gemäß `src/types/index.ts`; i18n-Keys (`t.simple.*`, `t.nav.simpleHome|myWorkflows|results`) in Task 1 definiert und in 6/8/9/10/11 verwendet.

**Reihenfolge-Hinweis:** Task 11 vor Task 6 (SimpleHome importiert SimpleOnboarding).
