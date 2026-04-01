import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { useChains, useChain, useCreateChain, useDeleteChain, useExecuteChain } from '../useChains';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useChains', () => {
  it('fetches chains successfully', async () => {
    const { result } = renderHook(() => useChains(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data![0].name).toBe('Test Chain');
  });

  it('returns loading state initially', () => {
    const { result } = renderHook(() => useChains(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useChain', () => {
  it('fetches a single chain', async () => {
    const { result } = renderHook(() => useChain('ch1'), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('ch1');
  });

  it('does not fetch when disabled', () => {
    const { result } = renderHook(() => useChain(''), { wrapper: createWrapper() });
    expect(result.current.fetchStatus).toBe('idle');
  });
});

describe('useCreateChain', () => {
  it('creates a chain via mutation', async () => {
    const { result } = renderHook(() => useCreateChain(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ name: 'New Chain', entries: [] });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('ch2');
  });
});

describe('useDeleteChain', () => {
  it('deletes a chain via mutation', async () => {
    const { result } = renderHook(() => useDeleteChain(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate('ch1');
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});

describe('useExecuteChain', () => {
  it('executes a chain via mutation', async () => {
    const { result } = renderHook(() => useExecuteChain(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ id: 'ch1' });
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('cr1');
  });
});
