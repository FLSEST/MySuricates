import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { useTodoTriggers, useCreateTodoTrigger, useDeleteTodoTrigger } from '../useTodoTriggers';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useTodoTriggers', () => {
  it('fetches todo triggers successfully', async () => {
    const { result } = renderHook(() => useTodoTriggers(), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toHaveLength(1);
    expect(result.current.data![0].tagPattern).toBe('backend,bug');
  });

  it('returns loading state initially', () => {
    const { result } = renderHook(() => useTodoTriggers(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useCreateTodoTrigger', () => {
  it('creates a todo trigger via mutation', async () => {
    const { result } = renderHook(() => useCreateTodoTrigger(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate({ tagPattern: 'frontend', targetWorkflowId: 'w1' } as any);
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('tt2');
  });
});

describe('useDeleteTodoTrigger', () => {
  it('deletes a todo trigger via mutation', async () => {
    const { result } = renderHook(() => useDeleteTodoTrigger(), { wrapper: createWrapper() });
    await act(async () => {
      result.current.mutate('tt1');
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
