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

import { useExecutionWebSocket } from '../useWebSocket';

function createWrapper() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
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
});
