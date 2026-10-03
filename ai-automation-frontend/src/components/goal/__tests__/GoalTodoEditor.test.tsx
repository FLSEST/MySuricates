import { failOnUnhandledRequest } from '../../../test/msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { en } from '../../../i18n/en';
import { useToastStore } from '../../../stores/toastStore';
import { GoalTodoEditor } from '../GoalTodoEditor';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  serverTitle = 'Privacy findings';
  useI18nStore.setState({ locale: 'en' });
  useToastStore.setState({ toasts: [] });
});

/** The server's title: the PUT changes it, so the refetch after the PUT brings a different TODO object. */
let serverTitle = 'Privacy findings';
const todo = () => ({
  id: 't1', title: serverTitle, description: null, projectId: 'p1', projectName: 'AutoPilot', status: 'OPEN',
  priority: 'MEDIUM', tags: [], linkedWorkflowId: null, dueDate: null,
  planning: {
    goalId: 'g1', goalTitle: 'Release', goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null,
    ratedBy: null, ratedAt: null, openQuestion: null,
  },
});

function serve(planningStatus: () => number) {
  const calls: { method: string; body: unknown }[] = [];
  server.use(
    http.get('/api/v1/todos/t1', () => HttpResponse.json(todo())),
    http.get('/api/v1/goals', () => HttpResponse.json([{ id: 'g1', title: 'Release' }])),
    http.get('/api/v1/goals/:id', ({ params }) => HttpResponse.json({
      goal: { id: params.id, title: 'Release', progress: {} }, todos: [], byProject: [], projects: [], workPackages: [], milestones: [],
    })),
    http.put('/api/v1/todos/t1', async ({ request }) => {
      const body = await request.json() as { title: string };
      calls.push({ method: 'PUT', body });
      serverTitle = body.title;
      return HttpResponse.json(todo());
    }),
    http.patch('/api/v1/todos/t1/planning', async ({ request }) => {
      calls.push({ method: 'PATCH', body: await request.json() });
      const status = planningStatus();
      return status === 200 ? HttpResponse.json(todo()) : HttpResponse.json({ error: 'planning refused' }, { status });
    }),
  );
  return calls;
}

const messages = () => useToastStore.getState().toasts.map((toast) => `${toast.type}: ${toast.message}`);

// bbee09e6 review: on the goal page the refetch after the PUT re-derived the planning form – a failed PATCH lost
// the edits, the second Save sent nothing, and "Todo updated" read as if all was saved.
describe('GoalTodoEditor – planning saved with the dialog', { timeout: 20_000 }, () => {
  it('keeps the planning edits when the PATCH fails, reports it, and a second Save sends them again', async () => {
    let status = 400;
    const calls = serve(() => status);
    const onClose = vi.fn();
    renderWithProviders(<GoalTodoEditor todoId="t1" onClose={onClose} />);

    fireEvent.change(await screen.findByRole('combobox', { name: 'Complexity' }), { target: { value: 'XL' } });
    fireEvent.change(screen.getByDisplayValue('Privacy findings'), { target: { value: 'Privacy findings (EU)' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(calls.map((c) => c.method)).toEqual(['PUT', 'PATCH']));
    await waitFor(() => expect(messages()).toEqual([
      `error: ${en.todos.savedWithoutPlanning}: planning refused`,
    ]));
    // the refetched TODO must not reset the form
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled());
    await waitFor(() => expect(calls.length).toBe(2));
    await new Promise((resolve) => setTimeout(resolve, 100)); // let the refetch of the TODO land
    expect(screen.getByRole('combobox', { name: 'Complexity' })).toHaveValue('XL');
    expect(onClose).not.toHaveBeenCalled();

    status = 200;
    useToastStore.setState({ toasts: [] });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(calls.map((c) => c.method)).toEqual(['PUT', 'PATCH', 'PUT', 'PATCH']);
    expect(calls[3]!.body).toEqual({ complexity: 'XL' });
    expect(messages()).toEqual([`success: ${en.toast.todoUpdated}`]);
  });
});
