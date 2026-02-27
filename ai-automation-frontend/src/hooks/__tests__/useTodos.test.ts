import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { useTodos, useTodoCount, useCreateTodo, useDeleteTodo } from '../useTodos';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: qc }, children);
}

describe('useTodos', () => {
  it('fetches paginated todos', async () => {
    const { result } = renderHook(() => useTodos(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data!.content).toHaveLength(1);
    expect(result.current.data!.content[0].title).toBe('Test Todo');
    expect(result.current.data!.totalElements).toBe(1);
  });

  it('returns loading state initially', () => {
    const { result } = renderHook(() => useTodos(), { wrapper: createWrapper() });
    expect(result.current.isLoading).toBe(true);
  });
});

describe('useTodoCount', () => {
  it('fetches todo counts', async () => {
    const { result } = renderHook(() => useTodoCount(), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(result.current.data).toEqual({ open: 3, inProgress: 1, done: 5 });
  });
});

describe('useCreateTodo', () => {
  it('creates a todo via mutation', async () => {
    const { result } = renderHook(() => useCreateTodo(), { wrapper: createWrapper() });

    await act(async () => {
      result.current.mutate({ title: 'New Todo', priority: 'MEDIUM' });
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.id).toBe('t2');
  });
});

describe('useDeleteTodo', () => {
  it('deletes a todo via mutation', async () => {
    const { result } = renderHook(() => useDeleteTodo(), { wrapper: createWrapper() });

    await act(async () => {
      result.current.mutate('t1');
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });
});
