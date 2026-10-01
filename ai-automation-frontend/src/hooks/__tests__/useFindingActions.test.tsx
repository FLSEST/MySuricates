import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode, SetStateAction } from 'react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { useToastStore } from '../../stores/toastStore';
import type { AuditFinding } from '../useAuditCenter';
import { useFindingActions } from '../useFindingActions';
import type { FindingFilter } from '../../utils/auditFindingFilter';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => {
  server.resetHandlers();
  useToastStore.setState({ toasts: [] });
});
afterAll(() => server.close());

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } } });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

const finding = { id: 'f-1', status: 'OPEN', dispositionNote: null, todos: [] } as unknown as AuditFinding;

/** Records what the page sends and answers like the backend. */
function serve() {
  const sent: Array<{ method: string; path: string; body: unknown }> = [];
  server.use(
    http.patch('/api/v1/audit-findings/:id', async ({ request }) => {
      const body = await request.json() as Partial<AuditFinding>;
      sent.push({ method: 'PATCH', path: new URL(request.url).pathname, body });
      return HttpResponse.json({ ...finding, ...body });
    }),
    http.post('/api/v1/audit-findings/:id/todo-links', async ({ request }) => {
      sent.push({ method: 'POST', path: new URL(request.url).pathname, body: await request.json() });
      return HttpResponse.json(finding);
    }),
  );
  return sent;
}

describe('useFindingActions', () => {
  it('asks for the reason first, then files the finding and offers its chip in a toast', async () => {
    const sent = serve();
    const setFilter = vi.fn<(value: SetStateAction<FindingFilter[]>) => void>();
    const { result } = renderHook(() => useFindingActions(['open'], setFilter), { wrapper });

    act(() => result.current.chooseStatus(finding, 'ACCEPTED_RISK'));
    expect(result.current.disposing).toEqual({ findingId: 'f-1', status: 'ACCEPTED_RISK', note: '' });
    act(() => result.current.setDispositionNote('  Tragbar  '));
    act(() => result.current.saveDisposition());

    await waitFor(() => expect(result.current.disposing).toBeNull());
    expect(sent).toEqual([{ method: 'PATCH', path: '/api/v1/audit-findings/f-1', body: { status: 'ACCEPTED_RISK', dispositionNote: 'Tragbar' } }]);
    const [toast] = useToastStore.getState().toasts;
    expect(toast!.message).toBe('Filed: now under “Accepted”');
    act(() => toast!.action!.onClick());
    const update = setFilter.mock.calls[0]![0] as (current: FindingFilter[]) => FindingFilter[];
    expect(update(['open'])).toEqual(['open', 'accepted']);
  });

  it('files other statuses at once and stays quiet while their chip is on', async () => {
    const sent = serve();
    const { result } = renderHook(() => useFindingActions(['open'], vi.fn()), { wrapper });

    act(() => result.current.chooseStatus(finding, 'AWAITING_VERIFICATION'));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.body).toEqual({ status: 'AWAITING_VERIFICATION' });
    expect(result.current.disposing).toBeNull();
    await waitFor(() => expect(result.current.saving).toBe(false));
    expect(useToastStore.getState().toasts).toEqual([]);
  });

  it('links a TODO by its trimmed id, ignores an empty one and closes the form afterwards', async () => {
    const sent = serve();
    const { result } = renderHook(() => useFindingActions(['open'], vi.fn()), { wrapper });

    act(() => result.current.toggleLinking('f-1'));
    expect(result.current.linking).toBe('f-1');
    act(() => result.current.submitLink('f-1'));
    expect(sent).toEqual([]);

    act(() => result.current.setTodoId(' t-42 '));
    act(() => result.current.submitLink('f-1'));

    await waitFor(() => expect(result.current.linking).toBeNull());
    expect(result.current.todoId).toBe('');
    expect(sent).toEqual([{ method: 'POST', path: '/api/v1/audit-findings/f-1/todo-links', body: { todoId: 't-42' } }]);
  });

  // 6ff1c580: a failed filing, TODO or link said nothing
  it('reports a failed filing with the server reason and keeps the reason form open', async () => {
    server.use(http.patch('/api/v1/audit-findings/:id', () =>
      HttpResponse.json({ error: 'dispositionNote required' }, { status: 400 })));
    const { result } = renderHook(() => useFindingActions(['open'], vi.fn()), { wrapper });

    act(() => result.current.chooseStatus(finding, 'DISMISSED'));
    act(() => result.current.saveDisposition());

    await waitFor(() => expect(useToastStore.getState().toasts).toHaveLength(1));
    expect(useToastStore.getState().toasts[0]).toMatchObject({
      type: 'error', message: 'Could not file the finding: dispositionNote required',
    });
    expect(result.current.disposing).toEqual({ findingId: 'f-1', status: 'DISMISSED', note: '' });
  });

  it('reports a failed link and a failed TODO, and keeps the link form with its id', async () => {
    server.use(
      http.post('/api/v1/audit-findings/:id/todo-links', () => HttpResponse.json({ error: 'TODO t-42 not found' }, { status: 404 })),
      http.post('/api/v1/audit-findings/:id/todos', () => new HttpResponse(null, { status: 500 })),
    );
    const { result } = renderHook(() => useFindingActions(['open'], vi.fn()), { wrapper });

    act(() => result.current.toggleLinking('f-1'));
    act(() => result.current.setTodoId('t-42'));
    act(() => result.current.submitLink('f-1'));
    await waitFor(() => expect(useToastStore.getState().toasts).toHaveLength(1));
    expect(useToastStore.getState().toasts[0]!.message).toBe('Could not link the TODO: TODO t-42 not found');
    expect(result.current.linking).toBe('f-1');
    expect(result.current.todoId).toBe('t-42');

    act(() => result.current.createTodo('f-1'));
    await waitFor(() => expect(useToastStore.getState().toasts).toHaveLength(2));
    expect(useToastStore.getState().toasts[1]).toMatchObject({ type: 'error', message: expect.stringMatching(/^Could not create the TODO/) });
  });

  it('closes the link form when the same finding is toggled again', () => {
    const { result } = renderHook(() => useFindingActions(['open'], vi.fn()), { wrapper });

    act(() => result.current.toggleLinking('f-1'));
    act(() => result.current.toggleLinking('f-2'));
    expect(result.current.linking).toBe('f-2');
    act(() => result.current.toggleLinking('f-2'));
    expect(result.current.linking).toBeNull();
  });
});
