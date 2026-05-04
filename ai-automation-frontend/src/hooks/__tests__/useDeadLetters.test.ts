import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import {
  useDeadLetters,
  useRetryDeadLetter,
  useDiscardDeadLetter,
} from '../useDeadLetters';

const apiBase = '/api/v1';

const mocks = vi.hoisted(() => ({
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}));

vi.mock('../useToast', () => ({
  useToast: () => ({ success: mocks.toastSuccess, error: mocks.toastError }),
}));

vi.mock('../../i18n', () => ({
  useT: () => ({
    toast: {
      dlqRetried: 'Retried',
      dlqRetryFailed: 'Retry failed',
      dlqDiscarded: 'Discarded',
      dlqDiscardFailed: 'Discard failed',
    },
  }),
}));

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
  const Wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client }, children);
  return { Wrapper, client };
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => {
  server.resetHandlers();
  vi.clearAllMocks();
});
afterAll(() => server.close());

describe('useDeadLetters', () => {
  it('should_return_letters_when_query_succeeds', async () => {
    const payload = {
      content: [{ id: '1', status: 'OPEN', errorMessage: 'boom', retryCount: 0 }],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    };
    server.use(
      http.get(`${apiBase}/dlq`, ({ request }) => {
        const url = new URL(request.url);
        expect(url.searchParams.get('status')).toBeNull();
        expect(url.searchParams.get('page')).toBe('0');
        expect(url.searchParams.get('size')).toBe('20');
        return HttpResponse.json(payload);
      }),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useDeadLetters('ALL', 0, 20), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(payload);
  });

  it('should_send_status_param_when_status_is_not_ALL', async () => {
    let capturedStatus: string | null = null;
    server.use(
      http.get(`${apiBase}/dlq`, ({ request }) => {
        capturedStatus = new URL(request.url).searchParams.get('status');
        return HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 50 });
      }),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useDeadLetters('OPEN', 1, 50), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(capturedStatus).toBe('OPEN');
  });

  it('should_expose_error_when_request_fails', async () => {
    server.use(
      http.get(`${apiBase}/dlq`, () =>
        HttpResponse.json({ error: 'boom' }, { status: 500 }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useDeadLetters('ALL', 0, 20), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });
});

describe('useRetryDeadLetter', () => {
  it('should_invalidate_dlq_and_show_success_toast_when_retry_succeeds', async () => {
    server.use(
      http.post(`${apiBase}/dlq/abc/retry`, () => HttpResponse.json({ id: 'abc' })),
    );
    const { Wrapper, client } = createWrapper();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');
    const { result } = renderHook(() => useRetryDeadLetter(), { wrapper: Wrapper });
    await act(async () => {
      await result.current.mutateAsync('abc');
    });
    expect(mocks.toastSuccess).toHaveBeenCalledWith('Retried');
    expect(invalidateSpy).toHaveBeenCalledWith(expect.objectContaining({ queryKey: ['dlq'] }));
  });

  it('should_show_error_toast_when_retry_fails', async () => {
    server.use(
      http.post(`${apiBase}/dlq/bad/retry`, () =>
        HttpResponse.json({ error: 'nope' }, { status: 500 }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useRetryDeadLetter(), { wrapper: Wrapper });
    await expect(
      act(async () => {
        await result.current.mutateAsync('bad');
      }),
    ).rejects.toBeDefined();
    await waitFor(() => {
      expect(mocks.toastError).toHaveBeenCalledWith('Retry failed: nope');
    });
  });
});

describe('useDiscardDeadLetter', () => {
  it('should_invalidate_dlq_and_show_success_toast_when_discard_succeeds', async () => {
    server.use(
      http.delete(`${apiBase}/dlq/xyz`, () => new HttpResponse(null, { status: 204 })),
    );
    const { Wrapper, client } = createWrapper();
    const invalidateSpy = vi.spyOn(client, 'invalidateQueries');
    const { result } = renderHook(() => useDiscardDeadLetter(), { wrapper: Wrapper });
    await act(async () => {
      await result.current.mutateAsync('xyz');
    });
    expect(mocks.toastSuccess).toHaveBeenCalledWith('Discarded');
    expect(invalidateSpy).toHaveBeenCalledWith(expect.objectContaining({ queryKey: ['dlq'] }));
  });

  it('should_show_error_toast_when_discard_fails', async () => {
    server.use(
      http.delete(`${apiBase}/dlq/bad`, () =>
        HttpResponse.json({ error: 'nope' }, { status: 500 }),
      ),
    );
    const { Wrapper } = createWrapper();
    const { result } = renderHook(() => useDiscardDeadLetter(), { wrapper: Wrapper });
    await expect(
      act(async () => {
        await result.current.mutateAsync('bad');
      }),
    ).rejects.toBeDefined();
    await waitFor(() => {
      expect(mocks.toastError).toHaveBeenCalledWith('Discard failed: nope');
    });
  });
});
