import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ReactNode, createElement } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useTodoEditor } from '../useTodoEditor';
import type { Todo } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => {
  server.resetHandlers();
  vi.useRealTimers();
});
afterAll(() => server.close());

function createWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  return ({ children }: { children: ReactNode }) => createElement(QueryClientProvider, { client: qc }, children);
}

const linked = {
  id: 't-deep', title: 'Deep linked todo', description: null, status: 'DONE', priority: 'HIGH', tags: ['frontend'],
  projectId: 'p1', linkedWorkflowId: null, dueDate: null,
} as unknown as Todo;

function filters(url: string, overrides: Record<string, unknown> = {}) {
  return {
    searchParams: new URLSearchParams(url),
    statusFilter: 'ACTIVE' as const,
    setStatusFilter: vi.fn(),
    tagFilter: 'backend',
    setTagFilter: vi.fn(),
    setProjectFilter: vi.fn(),
    ...overrides,
  };
}

describe('useTodoEditor', () => {
  it('opens a todo with empty strings for missing fields and saves form and tags', async () => {
    const saved: unknown[] = [];
    server.use(http.put('/api/v1/todos/:id', async ({ request, params }) => {
      saved.push({ id: params.id, ...(await request.json() as object) });
      return HttpResponse.json(linked);
    }));
    const { result } = renderHook(() => useTodoEditor(filters('')), { wrapper: createWrapper() });

    act(() => result.current.openEdit(linked));
    expect(result.current.editForm).toEqual({
      title: 'Deep linked todo', description: '', status: 'DONE', priority: 'HIGH',
      projectId: 'p1', linkedWorkflowId: '', tags: ['frontend'], dueDate: '',
    });
    act(() => result.current.setEditTagsList(['frontend', 'ux']));
    act(() => result.current.save());

    await waitFor(() => expect(result.current.editTodo).toBeNull());
    expect(saved).toEqual([expect.objectContaining({ id: 't-deep', title: 'Deep linked todo', tags: ['frontend', 'ux'] })]);
    expect(result.current.highlightId).toBeNull();
  });

  it('opens the deep-linked todo once, releases hiding filters and highlights it until after closing', async () => {
    server.use(http.get('/api/v1/todos/:id', () => HttpResponse.json(linked)));
    const f = filters('todoId=t-deep');
    const { result, rerender } = renderHook(() => useTodoEditor(f), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.editTodo?.id).toBe('t-deep'));
    expect(result.current.highlightId).toBe('t-deep');
    expect(f.setProjectFilter).toHaveBeenCalledWith('p1');
    expect(f.setStatusFilter).toHaveBeenCalledWith('ALL');   // ACTIVE would hide a DONE todo
    expect(f.setTagFilter).toHaveBeenCalledWith('');          // 'backend' is not one of its tags

    vi.useFakeTimers();
    act(() => result.current.closeEdit());
    expect(result.current.editTodo).toBeNull();
    expect(result.current.highlightId).toBe('t-deep');
    act(() => { vi.advanceTimersByTime(2500); });
    expect(result.current.highlightId).toBeNull();

    // Dismissed stays dismissed while the param is still in the URL
    rerender();
    expect(result.current.editTodo).toBeNull();
  });

  it('keeps matching filters and the project of the link', async () => {
    server.use(http.get('/api/v1/todos/:id', () => HttpResponse.json(linked)));
    const f = filters('todoId=t-deep&projectId=p9', { statusFilter: 'DONE', tagFilter: 'frontend' });
    const { result } = renderHook(() => useTodoEditor(f), { wrapper: createWrapper() });

    await waitFor(() => expect(result.current.editTodo?.id).toBe('t-deep'));
    expect(f.setProjectFilter).not.toHaveBeenCalled();
    expect(f.setStatusFilter).not.toHaveBeenCalled();
    expect(f.setTagFilter).not.toHaveBeenCalled();
  });

  it('exports the open todo to the chosen tracker', async () => {
    const exports: unknown[] = [];
    server.use(http.post('/api/v1/todos/:id/export', async ({ request, params }) => {
      exports.push({ id: params.id, ...(await request.json() as object) });
      return HttpResponse.json({ provider: 'github', externalId: '1', url: null });
    }));
    const { result } = renderHook(() => useTodoEditor(filters('')), { wrapper: createWrapper() });

    act(() => result.current.exportTo('github', 'repo'));   // nothing open: nothing sent
    act(() => result.current.openEdit(linked));
    act(() => result.current.exportTo('github', 'repo'));
    await waitFor(() => expect(exports).toEqual([{ id: 't-deep', provider: 'github', target: 'repo' }]));
  });
});
