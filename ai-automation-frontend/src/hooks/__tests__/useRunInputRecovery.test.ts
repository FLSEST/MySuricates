import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useRunInputRecovery } from '../useRunInputRecovery';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function serveApproval(body: Record<string, unknown>) {
  server.use(http.get('/api/v1/executions/:runId/approval', () => HttpResponse.json(body)));
}

describe('useRunInputRecovery', () => {
  it('stellt eine wartende Freigabe wieder her und räumt sie auf Wunsch weg', async () => {
    serveApproval({ pending: true, blockId: 'b1', question: 'Weiter?', options: ['Ja'], rejectOptions: ['Nein'], stepIndex: 2 });
    const { result } = renderHook(() => useRunInputRecovery('run-1'));
    await waitFor(() => expect(result.current.recoveredApproval).not.toBeNull());
    expect(result.current.recoveredApproval).toEqual({
      blockId: 'b1', question: 'Weiter?', options: ['Ja'], rejectOptions: ['Nein'], stepIndex: 2,
    });
    expect(result.current.recoveredHumanInput).toBeNull();
    act(() => result.current.clearRecoveredApproval());
    expect(result.current.recoveredApproval).toBeNull();
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
});
