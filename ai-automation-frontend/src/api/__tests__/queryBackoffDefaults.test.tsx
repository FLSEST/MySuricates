/**
 * f3fb193f: Eine fehlgeschlagene Query hat keine Daten und `dataUpdatedAt = 0` — sie gilt
 * damit trotz `staleTime` als stale und feuert bei jedem Mount erneut. Solange ein
 * 429-Backoff läuft, darf das nicht passieren: weder als Netzanfrage noch als Aufruf der
 * queryFn. Umgekehrt muss sich die App nach Ablauf der Sperre von selbst erholen — `retry`
 * ist für 429 aus und `refetchOnMount` greift nur beim Mount, dauerhaft montierte Queries
 * blieben sonst bis zum Reload im Fehlerzustand.
 */
import React from 'react';
import { render, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { appQueryDefaults, installRateLimitRecovery } from '../queryDefaults';
import { api, resetRateLimitBackoff } from '../client';

const NOW = Date.parse('2026-09-14T12:00:00.000Z');
/** Retry-After des Mocks. */
const LOCK_MS = 5_000;

let queryFnCalls = 0;
let disposers: Array<() => void> = [];

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

function Probe() {
  useQuery({
    queryKey: ['probe'],
    queryFn: async () => {
      queryFnCalls += 1;
      return api.get<unknown>('/probe');
    },
  });
  return <div>probe</div>;
}

/** Query-Client wie in main.tsx: Defaults + Erholung nach Ablauf der Sperre. */
function createClient() {
  const queryClient = new QueryClient({ defaultOptions: appQueryDefaults });
  disposers.push(installRateLimitRecovery(queryClient));
  return queryClient;
}

function renderProbe(queryClient: QueryClient) {
  return render(
    <QueryClientProvider client={queryClient}>
      <Probe />
    </QueryClientProvider>,
  );
}

async function advance(ms: number) {
  await act(async () => { await vi.advanceTimersByTimeAsync(ms); });
}

describe('QueryClient-Defaults im 429-Backoff (f3fb193f)', () => {
  beforeEach(() => {
    queryFnCalls = 0;
    disposers = [];
    resetRateLimitBackoff();
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(429, { error: 'Rate limit exceeded', timestamp: 'x' }, { 'Retry-After': '5' })));
  });

  afterEach(() => {
    for (const dispose of disposers) dispose();
    disposers = [];
    vi.useRealTimers();
    vi.unstubAllGlobals();
    resetRateLimitBackoff();
  });

  it('feuert eine gescheiterte Query im Sperrfenster bei Remount nicht erneut', async () => {
    const queryClient = createClient();
    const view = renderProbe(queryClient);
    await advance(100);
    expect(queryFnCalls).toBe(1);

    view.unmount();
    renderProbe(queryClient);
    await advance(100);
    expect(queryFnCalls, 'Remount im Backoff-Fenster hat die queryFn erneut aufgerufen').toBe(1);
  });

  it('holt eine montierte Fehler-Query nach Ablauf der Sperre genau einmal nach', async () => {
    const queryClient = createClient();
    renderProbe(queryClient);
    await advance(100);
    expect(queryFnCalls).toBe(1);

    // Ohne Erholung bliebe die Query bis zum Reload im Fehlerzustand: retry ist für 429 aus
    // und refetchOnMount greift ohne Remount nicht.
    await advance(LOCK_MS);
    expect(queryFnCalls, 'nach Ablauf der Sperre wurde nicht nachgeholt').toBe(2);

    // …und genau einmal, nicht in einer Schleife.
    await advance(1_000);
    expect(queryFnCalls).toBe(2);
  });

  it('lässt nach Ablauf der Sperre einen Remount wieder zu', async () => {
    const queryClient = createClient();
    const view = renderProbe(queryClient);
    await advance(100);
    expect(queryFnCalls).toBe(1);

    // Vor Ablauf abmelden, damit hier nur der Remount zählt und nicht die Erholung.
    view.unmount();
    await advance(LOCK_MS + 100);
    expect(queryFnCalls).toBe(1);

    renderProbe(queryClient);
    await advance(100);
    expect(queryFnCalls).toBe(2);
  });

  it('wiederholt 429 nicht (Bestandsverhalten)', async () => {
    const queryClient = createClient();
    renderProbe(queryClient);
    await advance(LOCK_MS - 1_000);
    expect(queryFnCalls).toBe(1);
  });
});
