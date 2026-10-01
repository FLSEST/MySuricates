import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useWorkflowBulkSelection } from '../useWorkflowBulkSelection';
import type { Workflow } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

const wf = (id: string, name: string, autoTodo = false) =>
  ({ id, name, metadata: { autoTodo: { enabled: autoTodo } } }) as unknown as Workflow;
const list = [wf('w1', 'Deploy'), wf('w2', 'Lint'), wf('w3', 'Report')];

function setup(onDeleted = vi.fn()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  );
  return { ...renderHook(() => useWorkflowBulkSelection(list, onDeleted), { wrapper }), onDeleted };
}

describe('useWorkflowBulkSelection', () => {
  it('selects single workflows and whole projects; leaving the mode clears the selection', () => {
    const { result } = setup();
    act(() => result.current.toggleSelectMode());
    act(() => result.current.toggleWorkflowSelect('w1'));
    expect([...result.current.selectedIds]).toEqual(['w1']);

    act(() => result.current.toggleProjectSelect([list[0], list[1]]));
    expect([...result.current.selectedIds].sort()).toEqual(['w1', 'w2']);
    act(() => result.current.toggleProjectSelect([list[0], list[1]]));
    expect([...result.current.selectedIds]).toEqual([]);

    act(() => result.current.toggleWorkflowSelect('w3'));
    act(() => result.current.toggleSelectMode());
    expect(result.current.selectMode).toBe(false);
    expect(result.current.selectedIds.size).toBe(0);
  });

  it('asks before deleting the selection, deletes one after the other and reports the deleted ids', async () => {
    const deleted: string[] = [];
    server.use(http.delete('/api/v1/workflows/:id', ({ params }) => {
      deleted.push(params.id as string);
      return new HttpResponse(null, { status: 204 });
    }));
    const { result, onDeleted } = setup();
    act(() => result.current.requestDeleteSelected());
    expect(result.current.deleteConfirm.open).toBe(false);

    act(() => result.current.toggleWorkflowSelect('w3'));
    act(() => result.current.toggleWorkflowSelect('w1'));
    act(() => result.current.requestDeleteSelected());
    expect(result.current.deleteConfirm).toEqual({ open: true, ids: ['w3', 'w1'], names: ['Deploy', 'Report'] });

    await act(() => result.current.confirmDelete());
    expect(deleted).toEqual(['w3', 'w1']);
    expect(onDeleted).toHaveBeenCalledWith(['w3', 'w1']);
    expect(result.current.selectedIds.size).toBe(0);
    expect(result.current.deleteConfirm.open).toBe(false);
    expect(result.current.deleting).toBe(false);
  });

  it('cannot be cancelled while the deletes run', async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => { release = r; });
    server.use(http.delete('/api/v1/workflows/:id', async () => {
      await gate;
      return new HttpResponse(null, { status: 204 });
    }));
    const { result } = setup();
    act(() => result.current.requestDeleteSingle(list[1]));
    expect(result.current.deleteConfirm).toEqual({ open: true, ids: ['w2'], names: ['Lint'] });

    let done!: Promise<void>;
    act(() => { done = result.current.confirmDelete(); });
    await waitFor(() => expect(result.current.deleting).toBe(true));
    act(() => { result.current.cancelDelete(); });
    expect(result.current.deleteConfirm.open).toBe(true);

    release();
    await act(() => done);
    expect(result.current.deleteConfirm.open).toBe(false);
  });

  it('switches auto-TODO for every selected workflow', async () => {
    const bodies: unknown[] = [];
    server.use(http.put('/api/v1/workflows/:id', async ({ params, request }) => {
      bodies.push([params.id, await request.json()]);
      return HttpResponse.json({ id: params.id });
    }));
    const { result } = setup();
    act(() => result.current.toggleWorkflowSelect('w1'));
    act(() => result.current.toggleWorkflowSelect('w2'));
    act(() => result.current.bulkToggleAutoTodo(true));
    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies).toEqual([
      ['w1', { metadata: { autoTodo: { enabled: true } } }],
      ['w2', { metadata: { autoTodo: { enabled: true } } }],
    ]);
  });
});
