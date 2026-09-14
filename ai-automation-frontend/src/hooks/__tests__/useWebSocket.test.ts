import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';

// Capture STOMP callbacks for manual message injection
let onConnectCb: (() => void) | null = null;
let subscribeCb: ((msg: { body: string }) => void) | null = null;
const mockDeactivate = vi.fn();
const mockActivate = vi.fn();

vi.mock('@stomp/stompjs', () => {
  class MockClient {
    constructor(config: Record<string, unknown>) {
      onConnectCb = config.onConnect as () => void;
    }
    activate = mockActivate.mockImplementation(() => {
      if (onConnectCb) onConnectCb();
    });
    deactivate = mockDeactivate;
    subscribe = vi.fn().mockImplementation((_dest: string, cb: (msg: { body: string }) => void) => {
      subscribeCb = cb;
      return { id: 'sub-1', unsubscribe: vi.fn() };
    });
  }
  return { Client: MockClient };
});

vi.mock('sockjs-client', () => ({
  default: vi.fn(),
}));

const mockApiGet = vi.fn();
vi.mock('../../api/client', () => ({
  api: { get: (...args: unknown[]) => mockApiGet(...args) },
}));

import { useExecutionWebSocket } from '../useWebSocket';

let lastQueryClient: QueryClient | null = null;

function createWrapper() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  lastQueryClient = qc;
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

function liveBatch(bufferId: string, seqs: number[], stepIndex = 0, kind = 'line') {
  return {
    type: 'live_output', runId: 'run-1', bufferId, skipped: 0,
    lines: seqs.map((seq) => ({ seq, stepIndex, kind, text: `line ${seq}`, ts: 1_700_000_000_000 + seq })),
  };
}

function sendMessage(msg: Record<string, unknown>) {
  if (!subscribeCb) throw new Error('No subscription callback — hook not connected');
  subscribeCb({ body: JSON.stringify(msg) });
}

describe('useExecutionWebSocket', () => {
  beforeEach(() => {
    onConnectCb = null;
    subscribeCb = null;
    mockActivate.mockClear();
    mockDeactivate.mockClear();
    mockApiGet.mockReset();
    mockApiGet.mockResolvedValue({ runId: 'run-1', bufferId: null, available: false, firstSeq: 0, lastSeq: -1, skipped: 0, lines: [] });
  });

  // --- Connection lifecycle ---

  it('does not connect when runId is null', () => {
    const { result } = renderHook(() => useExecutionWebSocket(null), { wrapper: createWrapper() });

    expect(result.current.connected).toBe(false);
    expect(result.current.logs).toHaveLength(0);
    expect(mockActivate).not.toHaveBeenCalled();
  });

  it('connects and sets connected=true', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.connected).toBe(true));
    expect(mockActivate).toHaveBeenCalled();
  });

  it('deactivates on unmount', () => {
    const { unmount } = renderHook(() => useExecutionWebSocket('run-1'), { wrapper: createWrapper() });

    unmount();
    expect(mockDeactivate).toHaveBeenCalled();
  });

  // --- Step messages ---

  it('appends step messages to logs', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => {
      sendMessage({ stepIndex: 0, status: 'COMPLETED', input: 'echo hi', output: 'hello', durationMs: 100 });
    });

    await waitFor(() => expect(result.current.logs).toHaveLength(1));
    expect(result.current.logs[0].message).toContain('hello');
  });

  it('clearLogs empties log array', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => sendMessage({ stepIndex: 0, status: 'COMPLETED', input: '', output: 'out', durationMs: 0 }));
    await waitFor(() => expect(result.current.logs).toHaveLength(1));

    act(() => result.current.clearLogs());
    expect(result.current.logs).toHaveLength(0);
  });

  // --- Status messages ---

  it('handles status messages and adds to logs', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => sendMessage({ type: 'status', runId: 'run-1', status: 'COMPLETED', stepsCompleted: 3 }));

    await waitFor(() => {
      expect(result.current.logs).toHaveLength(1);
      expect(result.current.logs[0].message).toContain('COMPLETED');
      expect(result.current.logs[0].message).toContain('3 steps completed');
    });
  });

  it('clears pending dialogs on terminal status', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.connected).toBe(true));

    // Set up approval
    act(() => {
      sendMessage({ type: 'approval_required', blockId: 'b1', question: 'Proceed?', options: ['Yes', 'No'], stepIndex: 0 });
    });
    await waitFor(() => expect(result.current.pendingApproval).not.toBeNull());

    // Terminal status clears it
    act(() => sendMessage({ type: 'status', runId: 'run-1', status: 'FAILED' }));
    await waitFor(() => expect(result.current.pendingApproval).toBeNull());
  });

  // --- Approval messages ---

  it('sets and clears pendingApproval', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => {
      sendMessage({ type: 'approval_required', blockId: 'b1', question: 'OK?', options: ['Yes', 'No'], stepIndex: 2 });
    });

    await waitFor(() => {
      expect(result.current.pendingApproval).toEqual({
        blockId: 'b1', question: 'OK?', options: ['Yes', 'No'], stepIndex: 2,
      });
    });

    act(() => sendMessage({ type: 'approval_resolved', stepIndex: 2, answer: 'Yes' }));
    await waitFor(() => expect(result.current.pendingApproval).toBeNull());
  });

  // --- Human input messages ---

  it('sets and clears pendingHumanInput', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => {
      sendMessage({
        type: 'human_input_required', blockId: 'hi1', prompt: 'Enter name:',
        inputType: 'text', options: [], defaultValue: '', required: true,
        validationPattern: null, variableName: 'name', stepIndex: 1,
      });
    });

    await waitFor(() => {
      expect(result.current.pendingHumanInput).not.toBeNull();
      expect(result.current.pendingHumanInput!.prompt).toBe('Enter name:');
      expect(result.current.pendingHumanInput!.variableName).toBe('name');
    });

    act(() => sendMessage({ type: 'human_input_resolved', stepIndex: 1, answer: 'Alice' }));
    await waitFor(() => expect(result.current.pendingHumanInput).toBeNull());
  });

  // --- Agentic messages ---

  it('handles agentic_started → sets goal and maxIterations', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => {
      sendMessage({ type: 'agentic_started', stepIndex: 0, goal: 'Fix the bug', maxIterations: 5 });
    });

    await waitFor(() => {
      const state = result.current.agenticStates.get(0);
      expect(state).toBeDefined();
      expect(state!.goal).toBe('Fix the bug');
      expect(state!.maxIterations).toBe(5);
    });
  });

  it('handles full agentic iteration cycle', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => {
      sendMessage({ type: 'agentic_started', stepIndex: 0, goal: 'Deploy', maxIterations: 3 });
      sendMessage({ type: 'agentic_reasoning', stepIndex: 0, iteration: 1 });
      sendMessage({ type: 'agentic_reasoned', stepIndex: 0, iteration: 1, reasoning: 'Need to build first' });
      sendMessage({ type: 'agentic_acting', stepIndex: 0, iteration: 1, action: 'npm run build' });
      sendMessage({ type: 'agentic_acted', stepIndex: 0, iteration: 1, action: 'npm run build', output: 'Build successful', exitCode: 0 });
    });

    await waitFor(() => {
      const state = result.current.agenticStates.get(0);
      expect(state!.currentIteration).toBe(1);
      expect(state!.iterations).toHaveLength(1);
      const iter = state!.iterations[0];
      expect(iter.reasoning).toBe('Need to build first');
      expect(iter.output).toBe('Build successful');
      expect(iter.exitCode).toBe(0);
    });
  });

  it('handles agentic_goal_achieved', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => {
      sendMessage({ type: 'agentic_started', stepIndex: 0, goal: 'Test', maxIterations: 3 });
      sendMessage({ type: 'agentic_goal_achieved', stepIndex: 0, iteration: 1, summary: 'All tests pass' });
    });

    await waitFor(() => {
      const state = result.current.agenticStates.get(0);
      expect(state!.goalAchieved).toBe(true);
      expect(state!.finalSummary).toBe('All tests pass');
    });
  });

  it('handles agentic_error', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => {
      sendMessage({ type: 'agentic_started', stepIndex: 0, goal: 'Test', maxIterations: 3 });
      sendMessage({ type: 'agentic_error', stepIndex: 0, iteration: 1, phase: 'acting', error: 'Timeout' });
    });

    await waitFor(() => {
      const state = result.current.agenticStates.get(0);
      expect(state!.iterations).toHaveLength(1);
      expect(state!.iterations[0].phase).toBe('error');
      expect(state!.iterations[0].output).toBe('Timeout');
    });

    // Verify error log
    const errorLog = result.current.logs.find(l => l.level === 'ERROR');
    expect(errorLog).toBeDefined();
    expect(errorLog!.message).toContain('Timeout');
  });

  // --- ForEach messages ---

  it('handles forEach item lifecycle', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => {
      sendMessage({ type: 'foreach_item_started', stepIndex: 1, itemIndex: 0, item: 'file1.txt', totalItems: 3 });
    });

    await waitFor(() => {
      const state = result.current.forEachStates.get(1);
      expect(state).toBeDefined();
      expect(state!.totalItems).toBe(3);
      expect(state!.items).toHaveLength(1);
      expect(state!.items[0].status).toBe('running');
    });

    act(() => {
      sendMessage({ type: 'foreach_item_completed', stepIndex: 1, itemIndex: 0, item: 'file1.txt', totalItems: 3, status: 'COMPLETED', output: 'done' });
    });

    await waitFor(() => {
      const state = result.current.forEachStates.get(1);
      expect(state!.completedItems).toBe(1);
      expect(state!.items[0].status).toBe('completed');
      expect(state!.items[0].output).toBe('done');
    });
  });

  it('handles forEach item failure', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => {
      sendMessage({ type: 'foreach_item_started', stepIndex: 2, itemIndex: 0, item: 'bad.txt', totalItems: 1 });
      sendMessage({ type: 'foreach_item_completed', stepIndex: 2, itemIndex: 0, item: 'bad.txt', totalItems: 1, status: 'FAILED', output: 'error' });
    });

    await waitFor(() => {
      const state = result.current.forEachStates.get(2);
      expect(state!.items[0].status).toBe('failed');
    });

    // Verify error-level log for failed item
    const errorLog = result.current.logs.find(l => l.level === 'ERROR');
    expect(errorLog).toBeDefined();
  });

  // --- Live output ---

  it('appends a live batch in one update with stable ids and kinds', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.connected).toBe(true));
    await waitFor(() => expect(mockApiGet).toHaveBeenCalled());

    act(() => sendMessage({ ...liveBatch('buf-1', [0, 1], 2, 'tool') }));

    await waitFor(() => expect(result.current.logs).toHaveLength(2));
    expect(result.current.logs.map((l) => l.id)).toEqual(['live-buf-1-0', 'live-buf-1-1']);
    expect(result.current.logs[0]).toMatchObject({ kind: 'tool', stepIndex: 2, message: 'line 0', stepLabel: 'Step 3' });
  });

  it('ignores duplicate seqs and resets de-duplication when the bufferId changes', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(mockApiGet).toHaveBeenCalled());

    act(() => sendMessage(liveBatch('buf-1', [0, 1, 2])));
    act(() => sendMessage(liveBatch('buf-1', [1, 2, 3])));
    act(() => sendMessage(liveBatch('buf-2', [0])));

    await waitFor(() => expect(result.current.logs.map((l) => l.id)).toEqual(
      ['live-buf-1-0', 'live-buf-1-1', 'live-buf-1-2', 'live-buf-1-3', 'live-buf-2-0']));
  });

  it('replays buffered lines on connect and queues live batches until the replay is done', async () => {
    let resolveReplay: (value: unknown) => void = () => {};
    mockApiGet.mockReturnValue(new Promise((resolve) => { resolveReplay = resolve; }));
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(mockApiGet).toHaveBeenCalledWith('/executions/run-1/live-log?afterSeq=-1'));

    act(() => sendMessage(liveBatch('buf-1', [3, 4])));
    expect(result.current.logs).toHaveLength(0);

    await act(async () => {
      resolveReplay({ runId: 'run-1', bufferId: 'buf-1', available: true, firstSeq: 0, lastSeq: 3, skipped: 0,
        lines: liveBatch('buf-1', [0, 1, 2, 3]).lines });
    });

    await waitFor(() => expect(result.current.logs.map((l) => l.message)).toEqual(
      ['line 0', 'line 1', 'line 2', 'line 3', 'line 4']));
  });

  it('labels replayed lines with the step names from the snapshot', async () => {
    mockApiGet.mockResolvedValue({ runId: 'run-1', bufferId: 'buf-1', available: true, firstSeq: 0, lastSeq: 0, skipped: 0,
      stepNames: { '0': 'Build' }, lines: liveBatch('buf-1', [0], 0).lines });
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.logs).toHaveLength(1));
    expect(result.current.logs[0].stepLabel).toBe('Step 1 · Build');
  });

  it('keeps working when the replay request fails', async () => {
    mockApiGet.mockRejectedValue(new Error('offline'));
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(mockApiGet).toHaveBeenCalled());

    await act(async () => { await Promise.resolve(); });
    act(() => sendMessage(liveBatch('buf-1', [0])));

    await waitFor(() => expect(result.current.logs).toHaveLength(1));
  });

  it('inserts a skipped marker for missing seqs', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(mockApiGet).toHaveBeenCalled());

    act(() => sendMessage(liveBatch('buf-1', [0])));
    act(() => sendMessage(liveBatch('buf-1', [5, 6])));

    await waitFor(() => expect(result.current.logs).toHaveLength(4));
    expect(result.current.logs[1]).toMatchObject({ skipped: 4, level: 'WARN' });
  });

  it('does not invalidate run queries for live batches, but does for step and forEach messages', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.connected).toBe(true));
    const invalidate = vi.spyOn(lastQueryClient!, 'invalidateQueries');

    act(() => sendMessage(liveBatch('buf-1', [0, 1])));
    expect(invalidate).not.toHaveBeenCalled();

    act(() => sendMessage({ stepIndex: 0, status: 'RUNNING', input: '', output: '', durationMs: 0 }));
    act(() => sendMessage({ type: 'foreach_item_started', stepIndex: 1, itemIndex: 0, item: 'a', totalItems: 1 }));
    act(() => sendMessage({ type: 'command', runId: 'run-1', input: 'ls', output: 'x' }));
    expect(invalidate).toHaveBeenCalledTimes(2);
  });

  it('labels steps with their block name and closes live steps with status and duration only', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(mockApiGet).toHaveBeenCalled());

    act(() => sendMessage({ stepIndex: 1, status: 'RUNNING', input: '', output: '', durationMs: 0, blockType: 'shell', blockName: 'Build' }));
    act(() => sendMessage(liveBatch('buf-1', [0], 1)));
    act(() => sendMessage({ stepIndex: 1, status: 'COMPLETED', input: 'mvn', output: 'huge output', durationMs: 65_000, blockName: 'Build' }));

    await waitFor(() => expect(result.current.logs.map((l) => l.message)).toEqual(
      ['[Step 2 · Build] started', 'line 0', '[Step 2 · Build] COMPLETED (1m 5s)']));
    expect(result.current.logs[1].stepLabel).toBe('Step 2 · Build');
  });

  it('renders command and container messages instead of "[Step undefined]"', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => sendMessage({ type: 'container_skipped', runId: 'run-1', message: 'Host fallback' }));
    act(() => sendMessage({ type: 'command', runId: 'run-1', input: 'ls', output: 'a.txt' }));

    await waitFor(() => expect(result.current.logs.map((l) => l.message)).toEqual(
      ['[Container] Host fallback', '$ ls\na.txt']));
    expect(result.current.logs[0].level).toBe('WARN');
  });

  it('does not replay or stream when liveOutput is off', async () => {
    const { result } = renderHook(() => useExecutionWebSocket('run-1', 'wf-1', { liveOutput: false }), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.connected).toBe(true));

    act(() => sendMessage(liveBatch('buf-1', [0])));

    expect(mockApiGet).not.toHaveBeenCalled();
    expect(result.current.logs).toHaveLength(0);
  });

  it('clears the logs when the runId changes', async () => {
    const { result, rerender } = renderHook(({ id }) => useExecutionWebSocket(id, 'wf-1'), {
      wrapper: createWrapper(), initialProps: { id: 'run-1' },
    });
    await waitFor(() => expect(mockApiGet).toHaveBeenCalled());
    act(() => sendMessage(liveBatch('buf-1', [0])));
    await waitFor(() => expect(result.current.logs).toHaveLength(1));

    rerender({ id: 'run-2' });

    await waitFor(() => expect(result.current.logs).toHaveLength(0));
  });
});
