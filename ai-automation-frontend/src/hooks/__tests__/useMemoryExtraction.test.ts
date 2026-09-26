import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { createTestQueryClient } from '../../test/utils';
import { useMemoryExtraction } from '../useMemoryExtraction';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => {
  server.resetHandlers();
  vi.useRealTimers();
});
afterAll(() => server.close());

function wrapper({ children }: { children: ReactNode }) {
  return createElement(QueryClientProvider, { client: createTestQueryClient() }, children);
}

/** Answers the extraction once `release` is called; records every request body. */
function serveExtract(status = 200) {
  const bodies: unknown[] = [];
  let release = () => {};
  const answered = new Promise<void>((resolve) => { release = resolve; });
  server.use(http.post('/api/v1/workflows/:wfId/memory/extract', async ({ request }) => {
    bodies.push(await request.json());
    await answered;
    return status === 200
      ? HttpResponse.json({ id: 'm1', title: 'Neu', content: 'x', active: true })
      : HttpResponse.json({ error: 'kaputt' }, { status });
  }));
  return { bodies, release: () => release() };
}

describe('useMemoryExtraction', () => {
  it('sends the chosen runs and provider, then closes and clears the selection', async () => {
    const api = serveExtract();
    const { result } = renderHook(() => useMemoryExtraction('wf1'), { wrapper });
    act(() => result.current.show());
    act(() => result.current.setRunSelected('r1', true));
    act(() => result.current.setRunSelected('r2', true));
    act(() => result.current.setRunSelected('r1', false));
    act(() => result.current.setProvider('CODEX'));
    expect(result.current.open).toBe(true);
    expect(result.current.selectedRunIds).toEqual(['r2']);

    act(() => result.current.extract());
    await waitFor(() => expect(api.bodies).toEqual([{ runIds: ['r2'], provider: 'CODEX' }]));
    api.release();

    await waitFor(() => expect(result.current.open).toBe(false));
    expect(result.current.selectedRunIds).toEqual([]);
    expect(result.current.isPending).toBe(false);
  });

  it('lets the server pick the runs when none are chosen', async () => {
    const api = serveExtract();
    const { result } = renderHook(() => useMemoryExtraction('wf1'), { wrapper });
    act(() => result.current.extract());
    await waitFor(() => expect(api.bodies).toHaveLength(1));
    expect(api.bodies[0]).toEqual({ provider: 'CLAUDE' });
    api.release();
    await waitFor(() => expect(result.current.isPending).toBe(false));
  });

  it('counts seconds while the extraction runs and starts at zero for the next one', async () => {
    // Only the interval is faked; waitFor polls with setInterval, so this test waits on its own.
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
    const settle = async (done: () => boolean) => {
      for (let i = 0; i < 100 && !done(); i++) {
        await act(() => new Promise((r) => setTimeout(r, 10)));
      }
      expect(done()).toBe(true);
    };
    const first = serveExtract();
    const { result } = renderHook(() => useMemoryExtraction('wf1'), { wrapper });
    act(() => result.current.extract());
    await settle(() => result.current.isPending);
    act(() => { vi.advanceTimersByTime(3000); });
    expect(result.current.elapsedSeconds).toBe(3);
    first.release();
    await settle(() => !result.current.isPending);
    act(() => { vi.advanceTimersByTime(2000); });
    expect(result.current.elapsedSeconds).toBe(3);

    const second = serveExtract();
    act(() => result.current.extract());
    expect(result.current.elapsedSeconds).toBe(0);
    second.release();
    await settle(() => !result.current.isPending);
  });

  it('keeps the dialog and the selection when the extraction fails', async () => {
    const api = serveExtract(500);
    const { result } = renderHook(() => useMemoryExtraction('wf1'), { wrapper });
    act(() => result.current.show());
    act(() => result.current.setRunSelected('r1', true));
    act(() => result.current.extract());
    api.release();
    await waitFor(() => expect(api.bodies).toHaveLength(1));
    await waitFor(() => expect(result.current.isPending).toBe(false));
    expect(result.current.open).toBe(true);
    expect(result.current.selectedRunIds).toEqual(['r1']);
  });
});
