/**
 * Reproduktion zu d89b2cc1: Nach einem 429 hält die geöffnete App das Rate-Limit selbst voll.
 *
 * Die App-Hülle (ApiKeyGuard + SecurityBanner + eine Seite mit useSystemModules und
 * useAssistantSuggestions) wird mit einem fetch gerendert, das 429 mit `Retry-After: 5`
 * beantwortet. Gezählt werden echte Netzanfragen pro Pfad (ohne Query) über ein Fenster
 * von 2 s Fake-Zeit.
 *
 * Erwartung nach dem Fix: höchstens eine Netzanfrage pro Endpoint im Backoff-Fenster,
 * nach Ablauf des Fensters genau eine neue.
 *
 * Zwei Szenarien, weil `/system/auth-status` die Hülle steuert:
 *  - `all-429`: auch auth-status ist limitiert. Der Guard bleibt unentschieden und zeigt
 *    nichts — es darf trotzdem nur eine auth-status-Anfrage geben (zwei Guards + StrictMode).
 *  - `security-off`: auth-status antwortet 200, alles andere 429. So ist die App montiert
 *    wie im beobachteten Vorfall und die Query-Endpunkte sind messbar.
 */
import React from 'react';
import { render, act, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiKeyGuard } from '../ApiKeyGuard';
import { SecurityBanner } from '../SecurityBanner';
import { useSystemModules, isModuleDisabledError } from '../../../hooks/useModules';
import { useAssistantSuggestions } from '../../../hooks/useAssistant';
import { resetAuthStatusCache, resetRateLimitBackoff } from '../../../api/client';
import { useSettingsStore } from '../../../stores/settingsStore';
import { useI18nStore } from '../../../i18n';

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

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

/** Installiert den zählenden fetch-Mock. `authStatus` steuert nur /system/auth-status. */
function stubFetch(authStatus: 'rate-limited' | 'security-off') {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = pathOf(input);
    requests.push(path);
    if (path === '/system/auth-status' && authStatus === 'security-off') {
      return jsonResponse({ securityEnabled: false });
    }
    return rateLimitedResponse();
  }));
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

// StrictMode wie in main.tsx: das Doppelmount des Dev-Servers gehört zum reproduzierten
// Verhalten und darf die Anfragezahl nicht verdoppeln.
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

function prepare() {
  requests = [];
  resetRateLimitBackoff();
  resetAuthStatusCache();
  useSettingsStore.setState({ apiKey: '' });
  vi.useFakeTimers();
}

function teardown() {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  useSettingsStore.setState({ apiKey: '' });
}

describe('429 storm reproduction (d89b2cc1)', () => {
  beforeEach(() => {
    prepare();
    stubFetch('security-off');
  });

  afterEach(teardown);

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
    resetAuthStatusCache(); // ein Reload würde den App-weiten Cache ebenfalls verlieren
    renderShell(queryClient);
    await advance(500);

    const after = countsByPath();
    for (const path of ['/system/auth-status', '/system/capabilities', '/assistant/suggestions']) {
      expect(after[path] - (before[path] ?? 0), `${path}: ${JSON.stringify(after)}`).toBe(1);
    }
  });
});

describe('429 auf /system/auth-status (d89b2cc1)', () => {
  beforeEach(() => {
    prepare();
    stubFetch('rate-limited');
  });

  afterEach(teardown);

  it('fragt auth-status trotz zweier Guards und StrictMode nur einmal an', async () => {
    renderShell(createAppQueryClient());
    await advance(2000);

    expect(countsByPath()['/system/auth-status']).toBe(1);
  });

  it('unterstellt bei 429 nicht „Security aus" und zeigt die App nicht ohne Key', async () => {
    const view = renderShell(createAppQueryClient());
    await advance(2000);

    // Guard bleibt unentschieden: weder Kinder noch das Key-Formular.
    expect(view.queryByTestId('page')).toBeNull();
    expect(view.container.textContent).toBe('');
    // …und ohne montierte Kinder gibt es auch keine Query-Anfragen.
    const counts = countsByPath();
    expect(counts['/system/capabilities']).toBeUndefined();
    expect(counts['/assistant/suggestions']).toBeUndefined();
  });
});

describe('ApiKeyGuard mountet die App nicht erneut (d89b2cc1)', () => {
  let mounts = 0;

  function CountingPage() {
    React.useEffect(() => { mounts += 1; }, []);
    useSystemModules();
    return <div>page</div>;
  }

  // Bewusst ohne StrictMode: dessen Doppelmount würde jede Zahl verdoppeln und damit
  // verdecken, ob der Guard selbst ein zweites Mal mountet. Gemessen wird genau das.
  function renderGuard(queryClient: QueryClient) {
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <ApiKeyGuard><CountingPage /></ApiKeyGuard>
        </MemoryRouter>
      </QueryClientProvider>,
    );
  }

  beforeEach(() => {
    mounts = 0;
    prepare();
    useSettingsStore.setState({ apiKey: 'stored-key' });
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = pathOf(input);
      requests.push(path);
      if (path === '/system/auth-status') return jsonResponse({ securityEnabled: true });
      // Alles andere ist rate-limited — wie im beobachteten Vorfall.
      return rateLimitedResponse();
    }));
  });

  afterEach(teardown);

  it('mountet die Kinder genau einmal, obwohl der gespeicherte Key geprüft wird', async () => {
    renderGuard(createAppQueryClient());
    await advance(2000);

    expect(mounts, `Kinder wurden ${mounts}× gemountet`).toBe(1);
    // Höchstens eine Anfrage: seit b6feccbc liegt nach dem 429 auf /projects zusätzlich eine
    // globale Kurzsperre an, die auch diesen ersten Aufruf abfangen darf.
    expect(countsByPath()['/system/capabilities'] ?? 0).toBeLessThanOrEqual(1);
  });

  it('behält den gespeicherten Key, wenn die Prüfung 429 bekommt', async () => {
    renderGuard(createAppQueryClient());
    await advance(2000);

    expect(useSettingsStore.getState().apiKey).toBe('stored-key');
  });
});

describe('Key-Eingabe läuft über den Backoff (b6feccbc)', () => {
  beforeEach(() => {
    prepare();
    useI18nStore.setState({ locale: 'de' });
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const path = pathOf(input);
      requests.push(path);
      if (path === '/system/auth-status') return jsonResponse({ securityEnabled: true });
      return rateLimitedResponse();
    }));
  });

  afterEach(() => {
    teardown();
    useI18nStore.setState({ locale: 'en' });
  });

  async function submitKey(view: ReturnType<typeof render>, key: string) {
    const input = view.container.querySelector('input[type="password"]')!;
    fireEvent.change(input, { target: { value: key } });
    const form = view.container.querySelector('form')!;
    await act(async () => { fireEvent.submit(form); });
    await advance(50);
  }

  it('fragt /projects im Sperrfenster nur einmal an und meldet das Rate-Limit', async () => {
    const view = render(
      <QueryClientProvider client={createAppQueryClient()}>
        <MemoryRouter>
          <ApiKeyGuard><div data-testid="page">page</div></ApiKeyGuard>
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await advance(50);

    await submitKey(view, 'typed-key-1');
    expect(requests.filter((p) => p === '/projects')).toHaveLength(1);
    expect(view.container.textContent).toContain('Zu viele Anfragen');

    // Zweiter Versuch im Sperrfenster: keine weitere Netzanfrage.
    await submitKey(view, 'typed-key-2');
    expect(requests.filter((p) => p === '/projects')).toHaveLength(1);
    expect(useSettingsStore.getState().apiKey).toBe('');
  });
});
