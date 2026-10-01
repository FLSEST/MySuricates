import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';

// --- STOMP / SockJS mocks (same pattern as useWebSocket.test.ts) ---
let onConnectCb: (() => void) | null = null;
let onDisconnectCb: (() => void) | null = null;
let onStompErrorCb: (() => void) | null = null;
let lastWsUrl: string | null = null;
let lastSockJsOptions: Record<string, unknown> | undefined;
let lastClientConfig: Record<string, unknown> | null = null;
// Captures the webSocketFactory so activate() can invoke it and trigger SockJS instantiation
let capturedWebSocketFactory: (() => unknown) | undefined;
// beforeConnect fetches the handshake ticket — stompjs awaits it before every connect attempt
let capturedBeforeConnect: (() => Promise<void>) | undefined;

interface SubscribeRecord {
  destination: string;
  callback: (msg: { body: string }) => void;
  unsubscribe: ReturnType<typeof vi.fn>;
}

const subscriptions: SubscribeRecord[] = [];
const mockActivate = vi.fn();
const mockDeactivate = vi.fn();


vi.mock('@stomp/stompjs', () => {
  class MockClient {
    connected = false;
    constructor(config: Record<string, unknown>) {
      lastClientConfig = config;
      onConnectCb = config.onConnect as () => void;
      onDisconnectCb = config.onDisconnect as () => void;
      onStompErrorCb = config.onStompError as () => void;
      capturedWebSocketFactory = config.webSocketFactory as (() => unknown) | undefined;
      capturedBeforeConnect = config.beforeConnect as (() => Promise<void>) | undefined;
    }
    activate = mockActivate.mockImplementation(() => {
      // The handshake itself (beforeConnect + webSocketFactory) is driven explicitly by the
      // ticket tests via openSocket(); everything else only needs the connected state.
      this.connected = true;
      if (onConnectCb) onConnectCb();
    });
    deactivate = mockDeactivate.mockImplementation(() => {
      this.connected = false;
      if (onDisconnectCb) onDisconnectCb();
    });
    subscribe = vi.fn().mockImplementation(
      (destination: string, callback: (msg: { body: string }) => void) => {
        const unsub = vi.fn();
        const rec: SubscribeRecord = { destination, callback, unsubscribe: unsub };
        subscriptions.push(rec);
        return { id: `sub-${subscriptions.length}`, unsubscribe: unsub };
      }
    );
  }
  return { Client: MockClient, ReconnectionTimeMode: { LINEAR: 0, EXPONENTIAL: 1 } };
});

// Use a class (not an arrow fn) so that `new SockJS(url)` works correctly
vi.mock('sockjs-client', () => ({
  default: class MockSockJS {
    constructor(url: string, _protocols?: unknown, options?: Record<string, unknown>) {
      lastWsUrl = url;
      lastSockJsOptions = options;
    }
  },
}));

const mockFetchWsTicket = vi.fn();
vi.mock('../../api/client', () => ({
  api: { fetchWsTicket: (...args: unknown[]) => mockFetchWsTicket(...args) },
}));

import { useAssistantWebSocket } from '../useAssistantWebSocket';
import { useSettingsStore } from '../../stores/settingsStore';

function findSubscription(destination: string): SubscribeRecord | undefined {
  return subscriptions.find((s) => s.destination === destination);
}

/** Runs the handshake the way stompjs does: beforeConnect, then webSocketFactory. */
async function openSocket() {
  await capturedBeforeConnect?.();
  capturedWebSocketFactory?.();
}

beforeEach(() => {
  onConnectCb = null;
  onDisconnectCb = null;
  onStompErrorCb = null;
  lastWsUrl = null;
  lastSockJsOptions = undefined;
  lastClientConfig = null;
  capturedWebSocketFactory = undefined;
  capturedBeforeConnect = undefined;
  subscriptions.length = 0;
  mockActivate.mockClear();
  mockDeactivate.mockClear();
  mockFetchWsTicket.mockReset();
  let ticketCount = 0;
  mockFetchWsTicket.mockImplementation(() => Promise.resolve(`ticket-${++ticketCount}`));
  // reset api key
  useSettingsStore.setState({ apiKey: '' });
});

describe('useAssistantWebSocket', () => {
  describe('connection lifecycle', () => {
    it('should_not_connect_when_no_ids_provided', () => {
      renderHook(() => useAssistantWebSocket(null, null));
      expect(mockActivate).not.toHaveBeenCalled();
    });

    it('should_connect_when_requestId_is_provided', async () => {
      renderHook(() => useAssistantWebSocket('req-1', null));
      await waitFor(() => expect(mockActivate).toHaveBeenCalled());
    });

    it('should_connect_when_only_sessionId_is_provided', async () => {
      renderHook(() => useAssistantWebSocket(null, 'sess-1'));
      await waitFor(() => expect(mockActivate).toHaveBeenCalled());
    });

    it('should_set_connected_true_after_onConnect_callback', async () => {
      const { result } = renderHook(() => useAssistantWebSocket('req-1', null));
      await waitFor(() => expect(result.current.connected).toBe(true));
    });

    it('should_fetch_a_ticket_and_never_put_the_apiKey_in_the_url', async () => {
      useSettingsStore.setState({ apiKey: 'secret-key' });
      renderHook(() => useAssistantWebSocket('req-1', null));
      await waitFor(() => expect(capturedBeforeConnect).toBeDefined());

      await act(async () => { await openSocket(); });

      expect(mockFetchWsTicket).toHaveBeenCalledTimes(1);
      expect(lastWsUrl).toContain('/ws?ticket=ticket-1');
      expect(lastWsUrl).not.toContain('apiKey=');
      expect(lastWsUrl).not.toContain('secret-key');
    });

    it('should_fetch_a_fresh_ticket_on_every_reconnect', async () => {
      renderHook(() => useAssistantWebSocket('req-1', null));
      await waitFor(() => expect(capturedBeforeConnect).toBeDefined());

      await act(async () => { await openSocket(); });
      await act(async () => { await openSocket(); });

      expect(mockFetchWsTicket).toHaveBeenCalledTimes(2);
      expect(lastWsUrl).toContain('ticket=ticket-2');
    });

    it('should_use_the_websocket_transport_only', async () => {
      renderHook(() => useAssistantWebSocket('req-1', null));
      await waitFor(() => expect(capturedBeforeConnect).toBeDefined());

      await act(async () => { await openSocket(); });

      // Ein SockJS-Fallback-Transport eröffnet eine neue Session und braucht ein zweites Ticket
      expect(lastSockJsOptions).toEqual({ transports: ['websocket'] });
    });

    it('should_back_off_exponentially_between_reconnects', async () => {
      renderHook(() => useAssistantWebSocket('req-1', null));
      await waitFor(() => expect(lastClientConfig).not.toBeNull());

      expect(lastClientConfig?.reconnectTimeMode).toBe(1 /* ReconnectionTimeMode.EXPONENTIAL */);
      expect(lastClientConfig?.maxReconnectDelay).toBe(60_000);
    });

    it('should_deactivate_client_on_unmount', async () => {
      const { unmount } = renderHook(() => useAssistantWebSocket('req-1', null));
      await waitFor(() => expect(mockActivate).toHaveBeenCalled());
      unmount();
      expect(mockDeactivate).toHaveBeenCalled();
    });

    it('should_set_connected_false_after_disconnect', async () => {
      const { result } = renderHook(() => useAssistantWebSocket('req-1', null));
      await waitFor(() => expect(result.current.connected).toBe(true));
      act(() => { onDisconnectCb?.(); });
      await waitFor(() => expect(result.current.connected).toBe(false));
    });

    it('should_set_connected_false_on_stomp_error', async () => {
      const { result } = renderHook(() => useAssistantWebSocket('req-1', null));
      await waitFor(() => expect(result.current.connected).toBe(true));
      act(() => { onStompErrorCb?.(); });
      await waitFor(() => expect(result.current.connected).toBe(false));
    });
  });

  describe('abrupt close (c5f3cff5)', () => {
    it('should_set_connected_false_when_the_socket_closes_without_receipt_and_subscribe_again_on_reconnect', async () => {
      const { result } = renderHook(() => useAssistantWebSocket('req-7', 'sess-7'));
      await waitFor(() => expect(result.current.connected).toBe(true));
      await waitFor(() => expect(findSubscription('/topic/assistant/req-7/status')).toBeDefined());
      const before = subscriptions.length;

      act(() => (lastClientConfig!.onWebSocketClose as (event: unknown) => void)({ code: 1006 }));
      expect(result.current.connected).toBe(false);

      act(() => onConnectCb!());
      expect(result.current.connected).toBe(true);
      const again = subscriptions.slice(before).map((s) => s.destination);
      expect(again).toEqual(expect.arrayContaining(['/topic/assistant/req-7/status', '/topic/assistant/sess-7/events']));
    });

    it('should_ignore_the_late_close_of_a_socket_it_already_left', async () => {
      const { result, rerender } = renderHook(({ id }) => useAssistantWebSocket(id, null), { initialProps: { id: 'req-1' as string | null } });
      await waitFor(() => expect(result.current.connected).toBe(true));
      const firstClient = lastClientConfig!;

      rerender({ id: null });
      rerender({ id: 'req-2' });
      await waitFor(() => expect(result.current.connected).toBe(true));
      expect(lastClientConfig).not.toBe(firstClient);

      act(() => (firstClient.onWebSocketClose as (event: unknown) => void)({ code: 1000 }));
      expect(result.current.connected).toBe(true);
    });
  });

  describe('status subscription', () => {
    it('should_subscribe_to_status_topic_for_requestId', async () => {
      renderHook(() => useAssistantWebSocket('req-42', null));
      await waitFor(() => {
        expect(findSubscription('/topic/assistant/req-42/status')).toBeDefined();
      });
    });

    it('should_update_currentPhase_when_status_message_arrives', async () => {
      const { result } = renderHook(() => useAssistantWebSocket('req-1', null));
      await waitFor(() => {
        expect(findSubscription('/topic/assistant/req-1/status')).toBeDefined();
      });
      const sub = findSubscription('/topic/assistant/req-1/status')!;

      const statusMsg = {
        type: 'assistant_status',
        requestId: 'req-1',
        phase: 'THINKING',
        detail: 'analysing',
        elapsedMs: 123,
        timestamp: '2026-05-04T10:00:00Z',
      };

      act(() => { sub.callback({ body: JSON.stringify(statusMsg) }); });

      await waitFor(() => {
        expect(result.current.currentPhase?.phase).toBe('THINKING');
        expect(result.current.currentPhase?.detail).toBe('analysing');
      });
    });

    it('should_clear_currentPhase_after_COMPLETED_phase', async () => {
      vi.useFakeTimers();
      try {
        const { result } = renderHook(() => useAssistantWebSocket('req-1', null));
        // activate runs synchronously through mockImplementation
        const sub = findSubscription('/topic/assistant/req-1/status')!;
        expect(sub).toBeDefined();

        const completed = {
          type: 'assistant_status',
          requestId: 'req-1',
          phase: 'COMPLETED',
          detail: null,
          elapsedMs: 1000,
          timestamp: '2026-05-04T10:00:00Z',
        };
        act(() => { sub.callback({ body: JSON.stringify(completed) }); });
        expect(result.current.currentPhase?.phase).toBe('COMPLETED');

        act(() => { vi.advanceTimersByTime(600); });
        expect(result.current.currentPhase).toBeNull();
      } finally {
        vi.useRealTimers();
      }
    });

    it('should_clear_currentPhase_after_ERROR_phase', async () => {
      vi.useFakeTimers();
      try {
        const { result } = renderHook(() => useAssistantWebSocket('req-1', null));
        const sub = findSubscription('/topic/assistant/req-1/status')!;
        const errMsg = {
          type: 'assistant_status',
          requestId: 'req-1',
          phase: 'ERROR',
          detail: 'oops',
          elapsedMs: 500,
          timestamp: '2026-05-04T10:00:00Z',
        };
        act(() => { sub.callback({ body: JSON.stringify(errMsg) }); });
        expect(result.current.currentPhase?.phase).toBe('ERROR');
        act(() => { vi.advanceTimersByTime(600); });
        expect(result.current.currentPhase).toBeNull();
      } finally {
        vi.useRealTimers();
      }
    });

    it('should_ignore_malformed_status_messages', async () => {
      const { result } = renderHook(() => useAssistantWebSocket('req-1', null));
      const sub = findSubscription('/topic/assistant/req-1/status')!;
      expect(() => {
        act(() => { sub.callback({ body: 'not-json' }); });
      }).not.toThrow();
      expect(result.current.currentPhase).toBeNull();
    });
  });

  describe('event subscription', () => {
    it('should_subscribe_to_events_topic_for_sessionId', async () => {
      renderHook(() => useAssistantWebSocket(null, 'sess-7'));
      await waitFor(() => {
        expect(findSubscription('/topic/assistant/sess-7/events')).toBeDefined();
      });
    });

    it('should_append_events_when_message_arrives', async () => {
      const { result } = renderHook(() => useAssistantWebSocket(null, 'sess-1'));
      await waitFor(() => {
        expect(findSubscription('/topic/assistant/sess-1/events')).toBeDefined();
      });
      const sub = findSubscription('/topic/assistant/sess-1/events')!;

      const event1 = {
        type: 'assistant_event',
        sessionId: 'sess-1',
        eventKind: 'TODO_CREATED',
        title: 'New TODO',
        detail: null,
        linkTo: null,
        timestamp: '2026-05-04T10:00:00Z',
      };
      const event2 = { ...event1, title: 'Second TODO' };

      act(() => {
        sub.callback({ body: JSON.stringify(event1) });
        sub.callback({ body: JSON.stringify(event2) });
      });

      await waitFor(() => {
        expect(result.current.events).toHaveLength(2);
        expect(result.current.events[0].title).toBe('New TODO');
        expect(result.current.events[1].title).toBe('Second TODO');
      });
    });

    it('should_clear_events_when_clearEvents_is_called', async () => {
      const { result } = renderHook(() => useAssistantWebSocket(null, 'sess-1'));
      await waitFor(() => {
        expect(findSubscription('/topic/assistant/sess-1/events')).toBeDefined();
      });
      const sub = findSubscription('/topic/assistant/sess-1/events')!;
      const event = {
        type: 'assistant_event',
        sessionId: 'sess-1',
        eventKind: 'X',
        title: 't',
        detail: null,
        linkTo: null,
        timestamp: 'now',
      };
      act(() => { sub.callback({ body: JSON.stringify(event) }); });
      await waitFor(() => expect(result.current.events).toHaveLength(1));

      act(() => { result.current.clearEvents(); });
      await waitFor(() => expect(result.current.events).toHaveLength(0));
    });

    it('should_ignore_malformed_event_messages', async () => {
      const { result } = renderHook(() => useAssistantWebSocket(null, 'sess-1'));
      const sub = findSubscription('/topic/assistant/sess-1/events')!;
      expect(() => {
        act(() => { sub.callback({ body: '{broken' }); });
      }).not.toThrow();
      expect(result.current.events).toHaveLength(0);
    });
  });

  describe('initial state', () => {
    it('should_return_empty_initial_state_when_no_ids', () => {
      const { result } = renderHook(() => useAssistantWebSocket(null, null));
      expect(result.current.currentPhase).toBeNull();
      expect(result.current.events).toEqual([]);
      expect(result.current.connected).toBe(false);
      expect(typeof result.current.clearEvents).toBe('function');
    });
  });
});
