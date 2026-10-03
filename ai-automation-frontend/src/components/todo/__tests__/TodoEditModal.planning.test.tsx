import { failOnUnhandledRequest } from '../../../test/msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { useTodoEditor } from '../../../hooks/useTodoEditor';
import { useToastStore } from '../../../stores/toastStore';
import { en } from '../../../i18n/en';
import { TodoEditModal } from '../TodoEditModal';
import type { Todo } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  useI18nStore.setState({ locale: 'en' });
  useToastStore.setState({ toasts: [] });
});

const messages = () => useToastStore.getState().toasts.map((toast) => `${toast.type}: ${toast.message}`);

function todoWith(planning: Record<string, unknown> = {}): Todo {
  return {
    id: 't1', title: 'Privacy findings', description: null, projectId: 'p1', projectName: 'AutoPilot', status: 'OPEN',
    priority: 'MEDIUM', tags: [], linkedWorkflowId: null, dueDate: null,
    planning: {
      goalId: 'g1', goalTitle: 'Release', goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null,
      ratedBy: null, ratedAt: null, openQuestion: null, ...planning,
    },
  } as unknown as Todo;
}

const filters = {
  searchParams: new URLSearchParams(''), statusFilter: 'ALL' as const, setStatusFilter: vi.fn(),
  tagFilter: '', setTagFilter: vi.fn(), setProjectFilter: vi.fn(),
};

/** The TODO page's wiring: useTodoEditor behind the dialog. */
function TodoPage({ todo }: { todo: Todo }) {
  const editor = useTodoEditor(filters);
  return (
    <>
      <button type="button" onClick={() => editor.openEdit(todo)}>open</button>
      <TodoEditModal
        editTodo={editor.editTodo} editForm={editor.editForm} editTagsList={editor.editTagsList}
        projects={[]} workflows={[]} allTags={[]} isLoading={editor.saving}
        onClose={editor.closeEdit} onFormChange={editor.setEditForm} onTagsChange={editor.setEditTagsList}
        onSave={editor.save} integrations={[]} onExport={editor.exportTo} exportLoading={editor.exporting}
      />
    </>
  );
}

/** Records PUT and PATCH in their order; `planningStatus` != 200 lets the planning call fail. */
function serve(planningStatus = 200) {
  const calls: { method: string; body: unknown }[] = [];
  server.use(
    http.get('/api/v1/goals', () => HttpResponse.json([{ id: 'g1', title: 'Release' }])),
    http.get('/api/v1/goals/:id', ({ params }) => HttpResponse.json({
      goal: { id: params.id, title: 'Release', progress: {} }, todos: [], byProject: [], projects: [], workPackages: [], milestones: [],
    })),
    http.put('/api/v1/todos/t1', async ({ request }) => {
      calls.push({ method: 'PUT', body: await request.json() });
      return HttpResponse.json(todoWith());
    }),
    http.patch('/api/v1/todos/t1/planning', async ({ request }) => {
      calls.push({ method: 'PATCH', body: await request.json() });
      return planningStatus === 200
        ? HttpResponse.json(todoWith({ complexity: 'L' }))
        : HttpResponse.json({ error: 'planning refused' }, { status: planningStatus });
    }),
  );
  return calls;
}

async function openDialog(todo = todoWith()) {
  renderWithProviders(<TodoPage todo={todo} />);
  fireEvent.click(screen.getByRole('button', { name: 'open' }));
  return screen.findByRole('combobox', { name: 'Complexity' });
}

// bbee09e6: „Save“ closed the dialog and silently dropped planning changes not saved with „Save planning“.
describe('TodoEditModal – one Save for TODO and planning', () => {
  it('saves the planning after the TODO and closes only then', async () => {
    const calls = serve();
    fireEvent.change(await openDialog(), { target: { value: 'L' } });
    expect(screen.queryByRole('button', { name: 'Save planning' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.map((c) => c.method)).toEqual(['PUT', 'PATCH']);
    expect(calls[1]!.body).toEqual({ complexity: 'L' });
    expect(messages()).toEqual([`success: ${en.toast.todoUpdated}`]);
  });

  it('sends no planning call when the planning is unchanged', async () => {
    const calls = serve();
    await openDialog();
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(calls.map((c) => c.method)).toEqual(['PUT']);
  });

  it('stays open with the edits when the planning call fails, and a second Save sends it again', async () => {
    const calls = serve(400);
    fireEvent.change(await openDialog(), { target: { value: 'XL' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(calls.map((c) => c.method)).toEqual(['PUT', 'PATCH']));
    // Both calls settled (Save is no longer busy) – and the dialog is still there
    await waitFor(() => expect(screen.getByRole('button', { name: 'Save' })).toBeEnabled());
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Complexity' })).toHaveValue('XL');
    // no "Todo updated" – the TODO is saved, its planning is not
    expect(messages()).toEqual([`error: ${en.todos.savedWithoutPlanning}: planning refused`]);

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(calls.map((c) => c.method)).toEqual(['PUT', 'PATCH', 'PUT', 'PATCH']));
  });

  it('cannot save a new question together with a decision', async () => {
    serve();
    await openDialog(todoWith({ openQuestion: 'Which licence?', decisionState: 'QUESTION', decision: null }));
    fireEvent.change(screen.getByLabelText('Decision'), { target: { value: 'MIT' } });
    fireEvent.change(screen.getByLabelText('Open question'), { target: { value: 'And GPL?' } });
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
  });
});
