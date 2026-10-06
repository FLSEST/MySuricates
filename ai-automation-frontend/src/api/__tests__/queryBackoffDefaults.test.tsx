/**
 * f3fb193f: A failed query has no data and `dataUpdatedAt = 0` — it therefore counts
 * as stale despite `staleTime` and fires again on every mount. While a
 * 429 backoff is running, that must not happen: neither as a network request nor as a call of
 * the queryFn. Conversely, the app must recover on its own once the block expires — `retry`
 * is off for 429 and `refetchOnMount` only applies on mount, permanently mounted queries
 * would otherwise stay in the error state until reload.
 */
import React from 'react';
import { render, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { appQueryDefaults, installRateLimitRecovery } from '../queryDefaults';
import { api, resetRateLimitBackoff } from '../client';

const NOW = Date.parse('2026-09-14T12:00:00.000Z');
/** Retry-After of the mock. */
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

/** Query client as in main.tsx: defaults + recovery after the block expires. */
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

  it('does not re-fire a failed query in the block window on remount', async () => {
    const queryClient = createClient();
    const view = renderProbe(queryClient);
    await advance(100);
    expect(queryFnCalls).toBe(1);

    view.unmount();
    renderProbe(queryClient);
    await advance(100);
    expect(queryFnCalls, 'Remount im Backoff-Fenster hat die queryFn erneut aufgerufen').toBe(1);
  });

  it('re-fetches a mounted error query exactly once after the block expires', async () => {
    const queryClient = createClient();
    renderProbe(queryClient);
    await advance(100);
    expect(queryFnCalls).toBe(1);

    // Without recovery the query would stay in the error state until reload: retry is off for 429
    // and refetchOnMount does not apply without a remount.
    await advance(LOCK_MS);
    expect(queryFnCalls, 'nach Ablauf der Sperre wurde nicht nachgeholt').toBe(2);

    // …and exactly once, not in a loop.
    await advance(1_000);
    expect(queryFnCalls).toBe(2);
  });

  it('allows a remount again after the block expires', async () => {
    const queryClient = createClient();
    const view = renderProbe(queryClient);
    await advance(100);
    expect(queryFnCalls).toBe(1);

    // Unsubscribe before expiry, so that only the remount counts here and not the recovery.
    view.unmount();
    await advance(LOCK_MS + 100);
    expect(queryFnCalls).toBe(1);

    renderProbe(queryClient);
    await advance(100);
    expect(queryFnCalls).toBe(2);
  });

  it('does not retry 429 (existing behaviour)', async () => {
    const queryClient = createClient();
    renderProbe(queryClient);
    await advance(LOCK_MS - 1_000);
    expect(queryFnCalls).toBe(1);
  });
});
