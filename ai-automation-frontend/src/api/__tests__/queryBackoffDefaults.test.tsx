/**
 * f3fb193f: Eine fehlgeschlagene Query hat keine Daten und `dataUpdatedAt = 0` — sie gilt
 * damit trotz `staleTime` als stale und feuert bei jedem Mount erneut. Solange ein
 * 429-Backoff läuft, darf das nicht passieren: weder als Netzanfrage noch als Aufruf der
 * queryFn.
 */
import React from 'react';
import { render, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { appQueryDefaults } from '../queryDefaults';
import { api, resetRateLimitBackoff } from '../client';

const NOW = Date.parse('2026-09-14T12:00:00.000Z');

let queryFnCalls = 0;

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
    resetRateLimitBackoff();
    vi.useFakeTimers();
    vi.setSystemTime(NOW);
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(429, { error: 'Rate limit exceeded', timestamp: 'x' }, { 'Retry-After': '5' })));
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    resetRateLimitBackoff();
  });

  it('feuert eine gescheiterte Query im Sperrfenster bei Remount nicht erneut', async () => {
    const queryClient = new QueryClient({ defaultOptions: appQueryDefaults });
    const view = renderProbe(queryClient);
    await advance(100);
    expect(queryFnCalls).toBe(1);

    view.unmount();
    renderProbe(queryClient);
    await advance(100);
    expect(queryFnCalls, 'Remount im Backoff-Fenster hat die queryFn erneut aufgerufen').toBe(1);
  });

  it('lässt nach Ablauf der Sperre genau einen neuen Versuch zu', async () => {
    const queryClient = new QueryClient({ defaultOptions: appQueryDefaults });
    const view = renderProbe(queryClient);
    await advance(100);
    expect(queryFnCalls).toBe(1);

    await advance(5_100);
    view.unmount();
    renderProbe(queryClient);
    await advance(100);
    expect(queryFnCalls).toBe(2);
  });

  it('wiederholt 429 nicht (Bestandsverhalten)', async () => {
    const queryClient = new QueryClient({ defaultOptions: appQueryDefaults });
    renderProbe(queryClient);
    await advance(5_000);
    expect(queryFnCalls).toBe(1);
  });
});
