import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';

// The socket is replaced: the test decides whether the topic is connected and delivers frames itself.
const socket = vi.hoisted(() => ({
  connected: false,
  topic: '' as string,
  deliver: null as ((data: unknown) => void) | null,
}));
vi.mock('../useTopicSocket', () => ({
  useTopicSocket: (topic: string, _enabled: boolean, onMessage: (data: unknown) => void) => {
    socket.topic = topic;
    socket.deliver = onMessage;
    return socket.connected;
  },
}));

import { useActivityLog, ACTIVITY_LOG_FALLBACK_POLL_MS, ACTIVITY_LOG_REFETCH_THROTTLE_MS } from '../useActivityLog';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => {
  server.resetHandlers();
  vi.useRealTimers();
});
afterAll(() => server.close());
beforeEach(() => {
  socket.connected = false;
  socket.deliver = null;
});

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

/** Counts GET /activity-log calls. */
function countRequests() {
  const calls = { n: 0 };
  server.use(http.get('/api/v1/activity-log', () => {
    calls.n += 1;
    return HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  }));
  return calls;
}

describe('useActivityLog', () => {
  it('fetches activity log successfully', async () => {
    const { result } = renderHook(() => useActivityLog(0, 20), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.content).toHaveLength(1);
    expect(result.current.data!.content[0].message).toBe('Test log');
  });

  it('returns loading state initially', () => {
    const { result } = renderHook(() => useActivityLog(0, 20), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });

  // TODO 4eea7a87: the backend broadcasts every entry on /topic/activity-log
  it('listens on the activity-log topic', () => {
    renderHook(() => useActivityLog(0, 20), { wrapper: createWrapper() });
    expect(socket.topic).toBe('/topic/activity-log');
  });

  it('reloads once per burst of new entries while the topic is connected, and does not poll', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    socket.connected = true;
    const calls = countRequests();
    const { result } = renderHook(() => useActivityLog(0, 20), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(calls.n).toBe(1);

    await act(async () => { await vi.advanceTimersByTimeAsync(ACTIVITY_LOG_FALLBACK_POLL_MS * 2); });
    expect(calls.n).toBe(1);

    act(() => {
      socket.deliver!({ id: 'a', message: 'one' });
      socket.deliver!({ id: 'b', message: 'two' });
      socket.deliver!({ id: 'c', message: 'three' });
    });
    await act(async () => { await vi.advanceTimersByTimeAsync(ACTIVITY_LOG_REFETCH_THROTTLE_MS); });
    await waitFor(() => expect(calls.n).toBe(2));
  });

  it('polls as before while the topic is not connected (old backend, network)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const calls = countRequests();
    const { result } = renderHook(() => useActivityLog(0, 20), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(calls.n).toBe(1);

    await act(async () => { await vi.advanceTimersByTimeAsync(ACTIVITY_LOG_FALLBACK_POLL_MS); });
    await waitFor(() => expect(calls.n).toBe(2));
  });
});
