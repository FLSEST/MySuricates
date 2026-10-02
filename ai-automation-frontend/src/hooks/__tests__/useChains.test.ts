import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useToastStore } from '../../stores/toastStore';
import { useI18nStore } from '../../i18n';
import { useChains, useChain, useCreateChain, useDeleteChain, useExecuteChain } from '../useChains';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
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

  describe('refused while a chain run is active (409 CHAIN_RUN_ACTIVE)', () => {
    beforeEach(() => useToastStore.setState({ toasts: [] }));
    afterEach(() => useI18nStore.setState({ locale: 'en' }));

    const refuse = () => server.use(http.delete('/api/v1/chains/ch1', () => HttpResponse.json(
      {
        code: 'STATE_CONFLICT',
        messageKey: 'CHAIN_RUN_ACTIVE',
        params: {},
        error: 'The chain still has a running or paused run. Wait for it to finish or cancel it, then delete the chain.',
      },
      { status: 409 },
    )));

    it.each([
      ['en', 'The chain still has a running or paused run.'],
      ['de', 'Die Kette hat noch einen laufenden oder pausierten Lauf.'],
    ] as const)('shows the reason as an error toast (%s)', async (locale, text) => {
      useI18nStore.setState({ locale });
      refuse();
      const { result } = renderHook(() => useDeleteChain(), { wrapper: createWrapper() });
      await act(async () => {
        result.current.mutate('ch1');
      });
      await waitFor(() => expect(result.current.isError).toBe(true));
      const toast = useToastStore.getState().toasts.at(-1);
      expect(toast?.type).toBe('error');
      expect(toast?.message).toContain(text);
    });
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
