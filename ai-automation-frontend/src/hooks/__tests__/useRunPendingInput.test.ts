import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useRunPendingInput, type LiveRunInput } from '../useRunPendingInput';
import type { PendingApproval } from '../useWebSocket';
import type { RunStatus, StepResult, WorkflowRun } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const APPROVAL: PendingApproval = { blockId: 'approval-1', question: 'Deploy?', options: ['Yes', 'No'], rejectOptions: ['No'], stepIndex: 1 };

function step(stepIndex: number, status: StepResult['status']): StepResult {
  return { id: `s${stepIndex}`, stepIndex, input: '', output: '', status, durationMs: 1, error: null };
}

function run(status: RunStatus, steps: StepResult[] = [step(0, 'COMPLETED')]): WorkflowRun {
  return {
    id: 'run-1', workflowId: 'wf-1', workflowName: 'Deploy', projectName: null, projectPath: null, status, steps,
    startedAt: '2026-09-25T08:00:00Z', completedAt: null, durationMs: null, userRating: null, error: null, metadata: {},
  };
}

/** The server side of the approval query: `open` decides whether the approval waits right now. */
function serveApproval() {
  const state = { open: false, calls: 0 };
  server.use(http.get('/api/v1/executions/:runId/approval', () => {
    state.calls++;
    return HttpResponse.json(state.open ? { pending: true, ...APPROVAL } : { pending: false });
  }));
  return state;
}

interface Props { runId: string; run: WorkflowRun | undefined; live: LiveRunInput }
const offline: LiveRunInput = { connected: false, approval: null, humanInput: null };

function renderPendingInput(initialProps: Props) {
  return renderHook((props: Props) => useRunPendingInput(props.runId, props.run, props.live), { initialProps });
}

describe('useRunPendingInput', () => {
  it('asks again once the socket connects — the approval began between the first query and the subscription', async () => {
    const endpoint = serveApproval();
    const { result, rerender } = renderPendingInput({ runId: 'run-1', run: run('RUNNING'), live: offline });
    await waitFor(() => expect(endpoint.calls).toBe(1));
    expect(result.current.approval).toBeNull();

    endpoint.open = true; // the socket event of this approval went out before anyone listened
    rerender({ runId: 'run-1', run: run('RUNNING'), live: { ...offline, connected: true } });

    await waitFor(() => expect(result.current.approval).toEqual(APPROVAL));
    expect(endpoint.calls).toBe(2);
  });

  it('asks again when the run moves on, but not while it stands still', async () => {
    const endpoint = serveApproval();
    const live = { ...offline, connected: true };
    const { result, rerender } = renderPendingInput({ runId: 'run-1', run: run('RUNNING'), live });
    await waitFor(() => expect(endpoint.calls).toBe(1));

    rerender({ runId: 'run-1', run: run('RUNNING'), live }); // a poll with the same state
    await new Promise((r) => setTimeout(r, 30));
    expect(endpoint.calls).toBe(1);

    endpoint.open = true;
    rerender({ runId: 'run-1', run: run('RUNNING', [step(0, 'COMPLETED'), step(1, 'RUNNING')]), live });
    await waitFor(() => expect(result.current.approval).toEqual(APPROVAL));
    expect(endpoint.calls).toBe(2);
  });

  it('does not ask for a run that is not running, and drops what it had once the run ends', async () => {
    const endpoint = serveApproval();
    endpoint.open = true;
    const { result, rerender } = renderPendingInput({ runId: 'run-1', run: undefined, live: offline });
    rerender({ runId: 'run-1', run: run('PENDING', []), live: offline });
    await new Promise((r) => setTimeout(r, 30));
    expect(endpoint.calls).toBe(0);

    rerender({ runId: 'run-1', run: run('PAUSED'), live: offline });
    await waitFor(() => expect(result.current.approval).toEqual(APPROVAL));

    rerender({ runId: 'run-1', run: run('CANCELLED'), live: { ...offline, connected: true } });
    expect(result.current.approval).toBeNull();
    await new Promise((r) => setTimeout(r, 30));
    expect(endpoint.calls).toBe(1);
  });

  it('closes a settled question at once, although the socket still reports it', async () => {
    const endpoint = serveApproval();
    endpoint.open = true;
    const live = { connected: true, approval: APPROVAL, humanInput: null };
    const { result, rerender } = renderPendingInput({ runId: 'run-1', run: run('RUNNING'), live });
    expect(result.current.approval).toEqual(APPROVAL);
    await waitFor(() => expect(endpoint.calls).toBe(1));

    act(() => result.current.settle(APPROVAL));
    expect(result.current.approval).toBeNull();

    // the query still returns it until the server has moved on — settled stays closed
    rerender({ runId: 'run-1', run: run('RUNNING', [step(0, 'COMPLETED'), step(1, 'RUNNING'), step(2, 'RUNNING')]), live });
    await waitFor(() => expect(endpoint.calls).toBe(2));
    expect(result.current.approval).toBeNull();

    // the next question of the same run opens again
    const next = { ...APPROVAL, blockId: 'approval-4', stepIndex: 4 };
    rerender({ runId: 'run-1', run: run('RUNNING'), live: { ...live, approval: next } });
    expect(result.current.approval).toEqual(next);
  });

  it('treats a human input the same way: queried on connect, closed when settled', async () => {
    let calls = 0;
    server.use(http.get('/api/v1/executions/:runId/approval', () => {
      calls++;
      return HttpResponse.json(calls === 1
        ? { pending: false }
        : { pending: true, type: 'human-input', blockId: 'input-2', question: 'Name?', stepIndex: 2 });
    }));
    const { result, rerender } = renderPendingInput({ runId: 'run-1', run: run('RUNNING'), live: offline });
    await waitFor(() => expect(calls).toBe(1));
    rerender({ runId: 'run-1', run: run('RUNNING'), live: { ...offline, connected: true } });
    await waitFor(() => expect(result.current.humanInput?.prompt).toBe('Name?'));
    expect(result.current.approval).toBeNull();

    act(() => result.current.settle(result.current.humanInput!));
    expect(result.current.humanInput).toBeNull();
  });

  it('shows the socket question before the queried one', async () => {
    const endpoint = serveApproval();
    endpoint.open = true;
    const live = { connected: true, approval: { ...APPROVAL, question: 'Live?' }, humanInput: null };
    const { result } = renderPendingInput({ runId: 'run-1', run: run('RUNNING'), live });
    await waitFor(() => expect(endpoint.calls).toBe(1));
    expect(result.current.approval?.question).toBe('Live?');
  });

  it('forgets settled questions when another run opens on the page', () => {
    // "Run again": the same workflow asks the same question at the same step — same key, new run
    serveApproval();
    const live = { connected: true, approval: APPROVAL, humanInput: null };
    const { result, rerender } = renderPendingInput({ runId: 'run-1', run: run('RUNNING'), live });
    act(() => result.current.settle(APPROVAL));
    expect(result.current.approval).toBeNull();

    rerender({ runId: 'run-2', run: { ...run('RUNNING'), id: 'run-2' }, live });
    expect(result.current.approval).toEqual(APPROVAL);
  });
});
