import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useSettingsStore } from '../../stores/settingsStore';
import { useTodoBoard } from '../useTodoBoard';
import type { Todo, TodoStatus } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useSettingsStore.setState({ listPageSize: 30, kanbanColumnSize: 20 }));

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client: qc }, children);
}

function todo(id: string, status: TodoStatus): Todo {
  return { id, title: id, status, tags: [], priority: 'MEDIUM', source: 'USER' } as unknown as Todo;
}

/** Answers every /todos request per status; records the status and size parameters. */
function serveTodos(requests: string[], failing?: TodoStatus) {
  server.use(http.get('/api/v1/todos', ({ request }) => {
    const url = new URL(request.url);
    const statuses = url.searchParams.getAll('status');
    requests.push(`${statuses.join('+') || 'any'}@${url.searchParams.get('size')}`);
    if (failing && statuses.includes(failing)) return HttpResponse.json({ message: 'boom' }, { status: 500 });
    const content = statuses.length === 1 ? [todo(`${statuses[0]}-1`, statuses[0] as TodoStatus)] : [todo('mixed-1', 'OPEN')];
    return HttpResponse.json({ content, page: 0, size: 20, totalElements: statuses.length === 1 ? 9 : 1, totalPages: 1 });
  }));
}

const listInput = {
  viewMode: 'list' as const, statusFilter: 'ACTIVE' as const,
  filterParams: { tag: 'ui' }, apiParams: { tag: 'ui', status: ['OPEN', 'IN_PROGRESS'] }, page: 0,
};

describe('useTodoBoard', () => {
  it('asks for one page in the list view and no board column', async () => {
    const requests: string[] = [];
    serveTodos(requests);
    const { result } = renderHook(() => useTodoBoard(listInput), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.isBoard).toBe(false);
    expect(result.current.todos?.map((t) => t.id)).toEqual(['mixed-1']);
    expect(requests).toEqual(['OPEN+IN_PROGRESS@30']);
  });

  it('asks once per column the status filter shows on the board', async () => {
    const requests: string[] = [];
    serveTodos(requests);
    const { result } = renderHook(() => useTodoBoard({ ...listInput, viewMode: 'board' }), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.boardStillLoading).toBe(false));
    expect(result.current.boardColumns.map((c) => [c.status, c.total])).toEqual([['OPEN', 9], ['IN_PROGRESS', 9]]);
    expect(result.current.todos?.map((t) => t.id)).toEqual(['OPEN-1', 'IN_PROGRESS-1']);
    expect([...requests].sort()).toEqual(['IN_PROGRESS@20', 'OPEN@20']);
  });

  it('shows the four standing columns for ALL and the archive only when filtered for it', async () => {
    serveTodos([]);
    const all = renderHook(() => useTodoBoard({ ...listInput, viewMode: 'board', statusFilter: 'ALL' }), { wrapper: createWrapper() });
    expect(all.result.current.boardColumns.map((c) => c.status)).toEqual(['OPEN', 'IN_PROGRESS', 'DONE', 'BACKLOG']);

    const archived = renderHook(() => useTodoBoard({ ...listInput, viewMode: 'board', statusFilter: 'ARCHIVED' }), { wrapper: createWrapper() });
    expect(archived.result.current.boardColumns.map((c) => c.status)).toEqual(['ARCHIVED']);
    await waitFor(() => expect(archived.result.current.isLoading).toBe(false));
  });

  it('reports the error of a failing column', async () => {
    serveTodos([], 'IN_PROGRESS');
    const { result } = renderHook(() => useTodoBoard({ ...listInput, viewMode: 'board' }), { wrapper: createWrapper() });
    await waitFor(() => expect(result.current.error).not.toBeNull());
    expect(result.current.boardColumns.find((c) => c.status === 'OPEN')!.todos).toHaveLength(1);
  });
});
