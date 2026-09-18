import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';

let onConnectCb: (() => void) | null = null;
let onDisconnectCb: (() => void) | null = null;
let onStompErrorCb: (() => void) | null = null;
let lastWsUrl: string | null = null;

interface SubscribeRecord { destination: string; callback: (msg: { body: string }) => void }
const subscriptions: SubscribeRecord[] = [];
const mockActivate = vi.fn();
const mockDeactivate = vi.fn();
let capturedWebSocketFactory: (() => unknown) | undefined;
let capturedBeforeConnect: (() => Promise<void>) | undefined;

vi.mock('@stomp/stompjs', () => {
  class MockClient {
    connected = false;
    constructor(config: Record<string, unknown>) {
      onConnectCb = config.onConnect as () => void;
      onDisconnectCb = config.onDisconnect as () => void;
      onStompErrorCb = config.onStompError as () => void;
      capturedWebSocketFactory = config.webSocketFactory as (() => unknown) | undefined;
      capturedBeforeConnect = config.beforeConnect as (() => Promise<void>) | undefined;
    }
    activate = mockActivate.mockImplementation(() => { this.connected = true; onConnectCb?.(); });
    deactivate = mockDeactivate.mockImplementation(() => { this.connected = false; onDisconnectCb?.(); });
    subscribe = vi.fn().mockImplementation((destination: string, callback: (msg: { body: string }) => void) => {
      subscriptions.push({ destination, callback });
      return { id: `sub-${subscriptions.length}`, unsubscribe: vi.fn() };
    });
  }
  return { Client: MockClient, ReconnectionTimeMode: { LINEAR: 0, EXPONENTIAL: 1 } };
});

vi.mock('sockjs-client', () => ({
  default: class MockSockJS { constructor(url: string) { lastWsUrl = url; } },
}));

const mockFetchWsTicket = vi.fn();
vi.mock('../../api/client', () => ({ api: { fetchWsTicket: () => mockFetchWsTicket() } }));

import { useRunCompletedSocket } from '../useRunCompletedSocket';

beforeEach(() => {
  subscriptions.length = 0;
  mockActivate.mockClear();
  mockDeactivate.mockClear();
  mockFetchWsTicket.mockReset().mockResolvedValue('ticket-1');
  lastWsUrl = null;
});

describe('useRunCompletedSocket', () => {
  it('connects only when enabled and subscribes to the dashboard topic', () => {
    const { result, rerender } = renderHook(({ on }: { on: boolean }) => useRunCompletedSocket(on, vi.fn()), {
      initialProps: { on: false },
    });
    expect(mockActivate).not.toHaveBeenCalled();
    expect(result.current).toBe(false);

    rerender({ on: true });

    expect(mockActivate).toHaveBeenCalledTimes(1);
    expect(subscriptions.map((sub) => sub.destination)).toEqual(['/topic/dashboard/runs']);
    expect(result.current).toBe(true);
  });

  it('reports connected as false again once the socket disconnects', () => {
    const { result } = renderHook(() => useRunCompletedSocket(true, vi.fn()));
    expect(result.current).toBe(true);

    act(() => { onDisconnectCb?.(); });

    expect(result.current).toBe(false);
  });

  it('reports connected as false again on a STOMP protocol error', () => {
    const { result } = renderHook(() => useRunCompletedSocket(true, vi.fn()));
    expect(result.current).toBe(true);

    act(() => { onStompErrorCb?.(); });

    expect(result.current).toBe(false);
  });

  it('reports run_completed messages and ignores anything else', () => {
    const onRunCompleted = vi.fn();
    renderHook(() => useRunCompletedSocket(true, onRunCompleted));
    const deliver = (body: string) => act(() => subscriptions[0]?.callback({ body }));

    deliver(JSON.stringify({ type: 'run_completed', runId: 'run-1', workflowId: 'wf-a1', status: 'COMPLETED' }));
    deliver(JSON.stringify({ type: 'something_else', workflowId: 'wf-a1' }));
    deliver('not json');
    deliver(JSON.stringify({ type: 'run_completed', workflowId: 42 }));

    expect(onRunCompleted).toHaveBeenCalledTimes(1);
    expect(onRunCompleted).toHaveBeenCalledWith({ type: 'run_completed', runId: 'run-1', workflowId: 'wf-a1', status: 'COMPLETED' });
  });

  it('reports the connection state and takes the ticket out of the header into the handshake url', async () => {
    const { result, unmount } = renderHook(() => useRunCompletedSocket(true, vi.fn()));
    expect(result.current).toBe(true);

    await act(async () => { await capturedBeforeConnect?.(); });
    capturedWebSocketFactory?.();
    expect(lastWsUrl).toBe(`${window.location.origin}/ws?ticket=ticket-1`);

    unmount();
    expect(mockDeactivate).toHaveBeenCalled();
  });
});
