import { describe, it, expect, beforeAll, afterAll, afterEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { type ReactNode, createElement } from 'react';
import { server } from '../../test/server';
import { useTodos, useTodoCount, useCreateTodo, useDeleteTodo, useResolveTodo, useProjectActiveTodos } from '../useTodos';
import { PROJECT_ACTIVE_TODOS_PREVIEW_SIZE, PROJECT_ACTIVE_TODOS_STALE_MS } from '../../components/todo/todoConstants';

const BASE = '/api/v1';

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

    expect(result.current.data).toEqual({ open: 3, inProgress: 1, done: 5, backlog: 0 });
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

describe('useResolveTodo', () => {
  it('starts a linked workflow run for a todo', async () => {
    const { result } = renderHook(() => useResolveTodo(), { wrapper: createWrapper() });

    await act(async () => {
      result.current.mutate('t1');
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data!.workflowId).toBe('w1');
  });
});

describe('useProjectActiveTodos', () => {
  const emptyPage = { content: [], page: 0, size: PROJECT_ACTIVE_TODOS_PREVIEW_SIZE, totalElements: 0, totalPages: 0 };

  it('asks for the cockpit preview page size and both active statuses', async () => {
    let requested: URL | null = null;
    server.use(http.get(`${BASE}/todos`, ({ request }) => {
      requested = new URL(request.url);
      return HttpResponse.json(emptyPage);
    }));

    const { result } = renderHook(() => useProjectActiveTodos('p1', true), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    const url = requested as unknown as URL;
    expect(url.searchParams.get('projectId')).toBe('p1');
    expect(url.searchParams.getAll('status')).toEqual(['OPEN', 'IN_PROGRESS']);
    expect(url.searchParams.get('size')).toBe(String(PROJECT_ACTIVE_TODOS_PREVIEW_SIZE));
  });

  it('does not refetch while the preview is still fresh (staleTime)', async () => {
    let calls = 0;
    server.use(http.get(`${BASE}/todos`, () => {
      calls += 1;
      return HttpResponse.json(emptyPage);
    }));
    // gcTime must outlive the unmount, otherwise the cache entry is dropped and refetched anyway.
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
    const wrapper = ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: qc }, children);

    const first = renderHook(() => useProjectActiveTodos('p1', true), { wrapper });
    await waitFor(() => expect(first.result.current.isSuccess).toBe(true));
    first.unmount();

    const second = renderHook(() => useProjectActiveTodos('p1', true), { wrapper });
    await waitFor(() => expect(second.result.current.isSuccess).toBe(true));

    expect(PROJECT_ACTIVE_TODOS_STALE_MS).toBeGreaterThan(0);
    expect(calls).toBe(1);
  });
});
