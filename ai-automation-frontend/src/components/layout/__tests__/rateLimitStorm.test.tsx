/**
 * Reproduktion zu d89b2cc1: Nach einem 429 hält die geöffnete App das Rate-Limit selbst voll.
 *
 * Die App-Hülle (ApiKeyGuard + SecurityBanner + eine Seite mit useSystemModules und
 * useAssistantSuggestions) wird mit einem fetch gerendert, das ausnahmslos 429 mit
 * `Retry-After: 5` beantwortet. Gezählt werden echte Netzanfragen pro Pfad (ohne Query)
 * über ein Fenster von 2 s Fake-Zeit.
 *
 * Erwartung nach dem Fix: höchstens eine Netzanfrage pro Endpoint im Backoff-Fenster,
 * nach Ablauf des Fensters genau eine neue.
 */
import React from 'react';
import { render, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiKeyGuard } from '../ApiKeyGuard';
import { SecurityBanner } from '../SecurityBanner';
import { useSystemModules, isModuleDisabledError } from '../../../hooks/useModules';
import { useAssistantSuggestions } from '../../../hooks/useAssistant';
import { resetAuthStatusCache, resetRateLimitBackoff } from '../../../api/client';
import { useSettingsStore } from '../../../stores/settingsStore';

const RETRY_AFTER_SECONDS = 5;

let requests: string[] = [];

function pathOf(input: RequestInfo | URL): string {
  const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
  const url = new URL(raw, 'http://localhost');
  return url.pathname.replace(/^\/api\/v1/, '');
}

function countsByPath(): Record<string, number> {
  return requests.reduce<Record<string, number>>((acc, path) => {
    acc[path] = (acc[path] ?? 0) + 1;
    return acc;
  }, {});
}

function rateLimitedResponse(): Response {
  return new Response(
    JSON.stringify({ error: 'Rate limit exceeded', timestamp: '2026-09-14T12:00:00Z' }),
    { status: 429, headers: { 'Content-Type': 'application/json', 'Retry-After': String(RETRY_AFTER_SECONDS) } },
  );
}

/** Query-Client mit denselben Defaults wie main.tsx (429 wird nicht wiederholt). */
function createAppQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: (failureCount, error) => {
          if (error && typeof error === 'object' && 'status' in error && (error as { status: number }).status === 429) return false;
          if (isModuleDisabledError(error)) return false;
          return failureCount < 1;
        },
        refetchOnWindowFocus: false,
      },
    },
  });
}

function Page() {
  useSystemModules();
  useAssistantSuggestions('workflows');
  return <div data-testid="page">page</div>;
}

function Shell() {
  return (
    <ApiKeyGuard>
      <SecurityBanner />
      <Page />
    </ApiKeyGuard>
  );
}

function renderShell(queryClient: QueryClient) {
  return render(
    <React.StrictMode>
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <Shell />
        </MemoryRouter>
      </QueryClientProvider>
    </React.StrictMode>,
  );
}

async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

describe('429 storm reproduction (d89b2cc1)', () => {
  beforeEach(() => {
    requests = [];
    resetRateLimitBackoff();
    resetAuthStatusCache();
    useSettingsStore.setState({ apiKey: '' });
    vi.useFakeTimers();
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      requests.push(pathOf(input));
      return rateLimitedResponse();
    }));
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('feuert im Backoff-Fenster höchstens eine Netzanfrage pro Endpoint', async () => {
    const queryClient = createAppQueryClient();
    const view = renderShell(queryClient);

    // Zwei Sekunden Fake-Zeit: darin darf jeder Endpoint höchstens einmal angefragt werden.
    await advance(2000);

    // Zusätzliche Remounts der Hülle (im echten Betrieb: Navigation, Panels, Guard-Rerender)
    // dürfen im Sperrfenster keine weiteren Netzanfragen erzeugen.
    view.unmount();
    renderShell(queryClient);
    await advance(500);

    const counts = countsByPath();
    for (const [path, count] of Object.entries(counts)) {
      expect(count, `${path} wurde ${count}× angefragt: ${JSON.stringify(counts)}`).toBeLessThanOrEqual(1);
    }
    expect(counts['/system/auth-status']).toBe(1);
    expect(counts['/system/capabilities']).toBe(1);
    expect(counts['/assistant/suggestions']).toBe(1);
  });

  it('lässt nach Ablauf des Retry-After-Fensters genau eine neue Anfrage pro Endpoint zu', async () => {
    const queryClient = createAppQueryClient();
    const view = renderShell(queryClient);
    await advance(2000);
    const before = countsByPath();

    // Sperrzeit abwarten, dann neu mounten.
    await advance(RETRY_AFTER_SECONDS * 1000 + 100);
    view.unmount();
    queryClient.clear();
    renderShell(queryClient);
    await advance(500);

    const after = countsByPath();
    for (const path of ['/system/auth-status', '/system/capabilities', '/assistant/suggestions']) {
      expect(after[path] - (before[path] ?? 0), `${path}: ${JSON.stringify(after)}`).toBe(1);
    }
  });
});

describe('ApiKeyGuard mountet die App nicht erneut (d89b2cc1)', () => {
  let mounts = 0;

  function CountingPage() {
    React.useEffect(() => { mounts += 1; }, []);
    useSystemModules();
    return <div>page</div>;
  }

  beforeEach(() => {
    mounts = 0;
    requests = [];
    resetRateLimitBackoff();
    resetAuthStatusCache();
    useSettingsStore.setState({ apiKey: 'stored-key' });
    vi.useFakeTimers();
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = pathOf(input);
      requests.push(path);
      if (path === '/system/auth-status') {
        return new Response(JSON.stringify({ securityEnabled: true }), {
          status: 200, headers: { 'Content-Type': 'application/json' },
        });
      }
      // Alles andere ist rate-limited — wie im beobachteten Vorfall.
      return rateLimitedResponse();
    }));
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    useSettingsStore.setState({ apiKey: '' });
  });

  it('mountet die Kinder genau einmal, obwohl der gespeicherte Key geprüft wird', async () => {
    const queryClient = createAppQueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <ApiKeyGuard><CountingPage /></ApiKeyGuard>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await advance(2000);

    expect(mounts, `Kinder wurden ${mounts}× gemountet`).toBe(1);
    expect(countsByPath()['/system/capabilities']).toBe(1);
  });

  it('behält den gespeicherten Key, wenn die Prüfung 429 bekommt', async () => {
    const queryClient = createAppQueryClient();
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <ApiKeyGuard><CountingPage /></ApiKeyGuard>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await advance(2000);

    expect(useSettingsStore.getState().apiKey).toBe('stored-key');
  });
});
