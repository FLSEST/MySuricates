import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { useNotifications, useCreateNotification, useDeleteNotification } from '../useNotifications';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useNotifications', () => {
  it('fetches notifications successfully', async () => {
    const { result } = renderHook(() => useNotifications(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data![0].name).toBe('Test Notification');
  });

  it('returns loading state initially', () => {
    const { result } = renderHook(() => useNotifications(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useCreateNotification', () => {
  it('creates a notification via mutation', async () => {
    const { result } = renderHook(() => useCreateNotification(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ name: 'New Notification', type: 'EMAIL' } as any);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('n2');
  });
});

describe('useDeleteNotification', () => {
  it('deletes a notification via mutation', async () => {
    const { result } = renderHook(() => useDeleteNotification(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate('n1');
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
