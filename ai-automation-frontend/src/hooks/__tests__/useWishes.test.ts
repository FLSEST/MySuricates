import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { useWishes, useWish, useCreateWish, useDeleteWish, useCancelWish, useCrystallizeWish } from '../useWishes';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useWishes', () => {
  it('fetches wishes successfully', async () => {
    const { result } = renderHook(() => useWishes(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data![0].title).toBe('Test Wish');
  });

  it('returns loading state initially', () => {
    const { result } = renderHook(() => useWishes(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useWish', () => {
  it('fetches a single wish', async () => {
    const { result } = renderHook(() => useWish('wi1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('wi1');
  });

  it('does not fetch when disabled', () => {
    const { result } = renderHook(() => useWish(null), { wrapper: createWrapper() });
    expect(result.current.fetchStatus).toBe('idle');
  });
});

describe('useCreateWish', () => {
  it('creates a wish via mutation', async () => {
    const { result } = renderHook(() => useCreateWish(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ title: 'New Wish' } as any);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useDeleteWish', () => {
  it('deletes a wish via mutation', async () => {
    const { result } = renderHook(() => useDeleteWish(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate('wi1');
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useCancelWish', () => {
  it('cancels a wish via mutation', async () => {
    const { result } = renderHook(() => useCancelWish(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate('wi1');
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useCrystallizeWish', () => {
  it('crystallizes a wish via mutation', async () => {
    const { result } = renderHook(() => useCrystallizeWish(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate('wi1');
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.templateId).toBe('tmpl1');
  });
});
