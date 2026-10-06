/**
 * Reproduction for d89b2cc1: after a 429, the open app fills the rate limit itself.
 *
 * The app shell (ApiKeyGuard + SecurityBanner + a page with useSystemModules and
 * useAssistantSuggestions) is rendered with a fetch that answers 429 with `Retry-After: 5`.
 * Real network requests are counted per path (without query) over a window
 * von 2 s Fake-Zeit.
 *
 * Expectation after the fix: at most one network request per endpoint in the backoff window,
 * after the window expires exactly one new one.
 *
 * Two scenarios, because `/system/auth-status` controls the shell:
 *  - `all-429`: auth-status is limited too. The guard stays undecided and shows
 *    nothing - there must still be only one auth-status request (two guards + StrictMode).
 *  - `security-off`: auth-status antwortet 200, alles andere 429. So ist die App montiert
 *    as in the observed incident and the query endpoints are measurable.
 */
import React from 'react';
import { render, act, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiKeyGuard } from '../ApiKeyGuard';
import { SecurityBanner } from '../SecurityBanner';
import { useSystemModules } from '../../../hooks/useModules';
import { useAssistantSuggestions } from '../../../hooks/useAssistant';
import { resetAuthStatusCache, resetRateLimitBackoff } from '../../../api/client';
import { appQueryDefaults, installRateLimitRecovery } from '../../../api/queryDefaults';
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

/** Installs the counting fetch mock. `authStatus` controls only /system/auth-status. */
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

let recoveryDisposers: Array<() => void> = [];

/** Query client exactly as in main.tsx: defaults + recovery after the lockout expires. */
function createAppQueryClient() {
  const queryClient = new QueryClient({ defaultOptions: appQueryDefaults });
  recoveryDisposers.push(installRateLimitRecovery(queryClient));
  return queryClient;
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

// StrictMode as in main.tsx: the dev server's double mount is part of the reproduced
// behavior and must not double the request count.
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
  recoveryDisposers = [];
  resetRateLimitBackoff();
  resetAuthStatusCache();
  useSettingsStore.setState({ apiKey: '' });
  vi.useFakeTimers();
}

function teardown() {
  for (const dispose of recoveryDisposers) dispose();
  recoveryDisposers = [];
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

  it('fires at most one network request per endpoint in the backoff window', async () => {
    const queryClient = createAppQueryClient();
    const view = renderShell(queryClient);

    // Two seconds of fake time: within it each endpoint may be requested at most once.
    await advance(2000);

    // Additional remounts of the shell (in real operation: navigation, panels, guard rerenders)
    // must not produce further network requests in the lockout window.
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

  it('allows exactly one new request per endpoint after the Retry-After window expires', async () => {
    const queryClient = createAppQueryClient();
    const view = renderShell(queryClient);
    await advance(2000);
    const before = countsByPath();

    // Sign out before expiry: what is measured here is the rebuild (reload), not the recovery
    // of mounted queries - that has its own test.
    view.unmount();
    await advance(RETRY_AFTER_SECONDS * 1000 + 100);
    queryClient.clear();
    resetAuthStatusCache(); // a reload would lose the app-wide cache as well
    renderShell(queryClient);
    await advance(500);

    const after = countsByPath();
    for (const path of ['/system/auth-status', '/system/capabilities', '/assistant/suggestions']) {
      expect(after[path] - (before[path] ?? 0), `${path}: ${JSON.stringify(after)}`).toBe(1);
    }
  });
});

describe('429 on /system/auth-status (d89b2cc1)', () => {
  beforeEach(() => {
    prepare();
    stubFetch('rate-limited');
  });

  afterEach(teardown);

  it('requests auth-status only once despite two guards and StrictMode', async () => {
    renderShell(createAppQueryClient());
    await advance(2000);

    expect(countsByPath()['/system/auth-status']).toBe(1);
  });

  it('does not assume "Security off" on 429 and does not show the app without a key', async () => {
    const view = renderShell(createAppQueryClient());
    await advance(2000);

    // Guard stays undecided: neither children nor the key form.
    expect(view.queryByTestId('page')).toBeNull();
    expect(view.container.textContent).toBe('');
    // ...and without mounted children there are no query requests either.
    const counts = countsByPath();
    expect(counts['/system/capabilities']).toBeUndefined();
    expect(counts['/assistant/suggestions']).toBeUndefined();
  });
});

describe('ApiKeyGuard does not mount the app again (d89b2cc1)', () => {
  let mounts = 0;

  function CountingPage() {
    React.useEffect(() => { mounts += 1; }, []);
    useSystemModules();
    return <div>page</div>;
  }

  // Deliberately without StrictMode: its double mount would double every number and thereby
  // hide whether the guard itself mounts a second time. That is exactly what is measured.
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
      // Everything else is rate-limited - as in the observed incident.
      return rateLimitedResponse();
    }));
  });

  afterEach(teardown);

  it('mounts the children exactly once although the stored key is checked', async () => {
    renderGuard(createAppQueryClient());
    await advance(2000);

    expect(mounts, `Kinder wurden ${mounts}× gemountet`).toBe(1);
    // Not a single request: the 429 on /projects (stored-key check) has set the global short lockout since b6feccbc,
    // the children mount right after - capabilities fails in the
    // client without going to the network.
    expect(countsByPath()['/system/capabilities'] ?? 0).toBe(0);

    // After the /projects lockout expires, the recovery fetches the query exactly once afterwards
    // (f3fb193f) - without it the mounted page would stay empty until reload.
    await advance(RETRY_AFTER_SECONDS * 1000 + 100);
    expect(countsByPath()['/system/capabilities']).toBe(1);
  });

  it('keeps the stored key when the check gets a 429', async () => {
    renderGuard(createAppQueryClient());
    await advance(2000);

    expect(useSettingsStore.getState().apiKey).toBe('stored-key');
  });
});

describe('Key entry goes through the backoff (b6feccbc)', () => {
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

  it('requests /projects only once in the lockout window and reports the rate limit', async () => {
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

    // Second attempt within the lock window: no further network request.
    await submitKey(view, 'typed-key-2');
    expect(requests.filter((p) => p === '/projects')).toHaveLength(1);
    expect(useSettingsStore.getState().apiKey).toBe('');
  });
});
