import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { http, HttpResponse, delay } from 'msw';
import { server } from '../../test/server';
import { useRunInputRecovery } from '../useRunInputRecovery';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function serveApproval(body: Record<string, unknown>) {
  server.use(http.get('/api/v1/executions/:runId/approval', () => HttpResponse.json(body)));
}

describe('useRunInputRecovery', () => {
  // Schließen nach Antwort oder per X: useRunPendingInput (settle) — der Hook hier liefert nur, was der Server meldet.
  it('stellt eine wartende Freigabe wieder her', async () => {
    serveApproval({ pending: true, blockId: 'b1', question: 'Weiter?', options: ['Ja'], rejectOptions: ['Nein'], stepIndex: 2 });
    const { result } = renderHook(() => useRunInputRecovery('run-1'));
    await waitFor(() => expect(result.current.recoveredApproval).not.toBeNull());
    expect(result.current.recoveredApproval).toEqual({
      blockId: 'b1', question: 'Weiter?', options: ['Ja'], rejectOptions: ['Nein'], stepIndex: 2,
    });
    expect(result.current.recoveredHumanInput).toBeNull();
  });

  it('stellt eine wartende Eingabe mit Vorgabewerten wieder her', async () => {
    serveApproval({ pending: true, type: 'human-input', blockId: 'b2', question: 'Name?', stepIndex: 0 });
    const { result } = renderHook(() => useRunInputRecovery('run-1'));
    await waitFor(() => expect(result.current.recoveredHumanInput).not.toBeNull());
    expect(result.current.recoveredHumanInput).toEqual({
      blockId: 'b2', prompt: 'Name?', inputType: 'text', options: [], defaultValue: '',
      required: true, validationPattern: null, variableName: 'user_input', stepIndex: 0,
    });
    expect(result.current.recoveredApproval).toBeNull();
  });

  it('bleibt leer ohne wartenden Schritt, bei Fehlern und ohne runId', async () => {
    let calls = 0;
    server.use(http.get('/api/v1/executions/:runId/approval', () => { calls++; return HttpResponse.json({ pending: false }); }));
    const { result } = renderHook(() => useRunInputRecovery('run-1'));
    await waitFor(() => expect(calls).toBe(1));
    expect(result.current.recoveredApproval).toBeNull();
    expect(result.current.recoveredHumanInput).toBeNull();

    server.use(http.get('/api/v1/executions/:runId/approval', () => HttpResponse.json({}, { status: 500 })));
    const failing = renderHook(() => useRunInputRecovery('run-2'));
    await new Promise((r) => setTimeout(r, 50));
    expect(failing.result.current.recoveredApproval).toBeNull();

    calls = 0;
    server.use(http.get('/api/v1/executions/:runId/approval', () => { calls++; return HttpResponse.json({ pending: false }); }));
    renderHook(() => useRunInputRecovery(undefined));
    await new Promise((r) => setTimeout(r, 50));
    expect(calls).toBe(0);
  });

  it('fragt bei neuem refreshKey erneut und räumt auf, sobald nichts mehr wartet', async () => {
    let pending = false;
    let calls = 0;
    server.use(http.get('/api/v1/executions/:runId/approval', () => {
      calls++;
      return HttpResponse.json(pending
        ? { pending: true, blockId: 'approval-1', question: 'Weiter?', options: ['Ja', 'Nein'], stepIndex: 1 }
        : { pending: false });
    }));
    const { result, rerender } = renderHook(({ key }) => useRunInputRecovery('run-1', key), { initialProps: { key: 'a' } });
    await waitFor(() => expect(calls).toBe(1));
    rerender({ key: 'a' });
    await new Promise((r) => setTimeout(r, 30));
    expect(calls).toBe(1);

    pending = true;
    rerender({ key: 'b' });
    await waitFor(() => expect(result.current.recoveredApproval?.question).toBe('Weiter?'));

    pending = false;
    rerender({ key: 'c' });
    await waitFor(() => expect(result.current.recoveredApproval).toBeNull());
    expect(calls).toBe(3);
  });

  it('übernimmt nur die Antwort der jüngsten Abfrage', async () => {
    let calls = 0;
    server.use(http.get('/api/v1/executions/:runId/approval', async () => {
      calls++;
      if (calls === 1) {
        // die erste Abfrage kommt zuletzt zurück, mit dem inzwischen veralteten Stand
        await delay(80);
        return HttpResponse.json({ pending: true, blockId: 'approval-0', question: 'Alt?', options: ['Ja'], stepIndex: 0 });
      }
      return HttpResponse.json({ pending: false });
    }));
    const { result, rerender } = renderHook(({ key }) => useRunInputRecovery('run-1', key), { initialProps: { key: 'a' } });
    await waitFor(() => expect(calls).toBe(1));
    rerender({ key: 'b' });
    await waitFor(() => expect(calls).toBe(2));
    await new Promise((r) => setTimeout(r, 150));
    expect(result.current.recoveredApproval).toBeNull();
  });

  it('gibt die Frage eines Laufs nicht an den nächsten weiter', async () => {
    server.use(http.get('/api/v1/executions/:runId/approval', async ({ params }) => {
      if (params.runId === 'run-2') await delay(80);
      return HttpResponse.json(params.runId === 'run-1'
        ? { pending: true, blockId: 'approval-3', question: 'Lauf 1?', options: ['Ja'], stepIndex: 3 }
        : { pending: false });
    }));
    const { result, rerender } = renderHook(({ runId }) => useRunInputRecovery(runId), { initialProps: { runId: 'run-1' } });
    await waitFor(() => expect(result.current.recoveredApproval).not.toBeNull());
    rerender({ runId: 'run-2' });
    expect(result.current.recoveredApproval).toBeNull();
  });
});
