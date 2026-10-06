import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { TodoPlanningPanel } from '../TodoPlanningPanel';
import { useTodoPlanningForm } from '../useTodoPlanningForm';
import type { GoalWorkPackage, Todo } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

const goals = [
  { id: 'g1', title: 'Release' },
  { id: 'g2', title: 'Docs' },
];

const startOf = (iso: string) => new Date(`${iso}T00:00:00`).toISOString();
const endOf = (iso: string) => new Date(`${iso}T23:59:59`).toISOString();

const wp = (id: string, title: string, projectId: string | null, span?: [string, string]): Partial<GoalWorkPackage> => ({
  id, goalId: 'g1', title, projectId, startDate: span ? startOf(span[0]) : null, endDate: span ? endOf(span[1]) : null,
  position: 0, openQuestions: 0, state: 'OPEN', total: 0, done: 0,
});

function todoWith(planning: Record<string, unknown>): Todo {
  return {
    id: 't1', title: 'Privacy findings', projectId: 'p1', projectName: 'AutoPilot', status: 'OPEN',
    planning: {
      goalId: 'g1', goalTitle: 'Release', goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null,
      ratedBy: null, ratedAt: null, openQuestion: null, ...planning,
    },
  } as unknown as Todo;
}

/** `legacy`: the goal answers like a backend before c60bf914 - without `workPackages`, the packages are called „milestones“. */
function serve(sent: unknown[], legacy = false) {
  const packages = [wp('w-legal', 'Legal', 'p1', ['2026-10-12', '2026-10-15']), wp('w-promo', 'Promotion', 'p2'), wp('w-docs', 'Handbook', 'p1')];
  server.use(
    http.get('/api/v1/goals', () => HttpResponse.json(goals)),
    http.get('/api/v1/goals/:id', ({ params }) => HttpResponse.json({
      goal: { id: params.id, title: 'x', progress: {} },
      todos: [],
      byProject: [],
      projects: [],
      ...(legacy
        ? { milestones: packages.map(({ startDate: _s, endDate, ...rest }) => ({ ...rest, dueDate: endDate })) }
        : { workPackages: params.id === 'g1' ? packages : [wp('w-other', 'Other goal', 'p1')], milestones: [] }),
    })),
    http.patch('/api/v1/todos/t1/planning', async ({ request }) => {
      sent.push(await request.json());
      return HttpResponse.json(todoWith({}));
    }),
  );
}

/** Panel plus the dialog's save step: since bbee09e6 the dialog holds the state and saves it with its own Save. */
function Planning({ todo }: { todo: Todo }) {
  const planning = useTodoPlanningForm(todo);
  return (
    <>
      <TodoPlanningPanel todo={todo} planning={planning} />
      <button type="button" disabled={planning.conflict} onClick={() => void planning.save()}>Dialog save</button>
    </>
  );
}

// c60bf914: the planning field picks the work package, filtered to the TODO's project
describe('TodoPlanningPanel – work package', () => {
  it('offers only packages of the TODO\'s project with their span and saves a new one as workPackageId', async () => {
    const sent: unknown[] = [];
    serve(sent);
    renderWithProviders(<Planning todo={todoWith({ workPackageId: 'w-legal' })} />);

    const select = await screen.findByRole('combobox', { name: 'Work package' });
    await waitFor(() => expect(within(select).getAllByRole('option').map((option) => option.textContent?.replace(/\s/g, ' ')))
      .toEqual(['No work package', 'Legal · 10/12 – 10/15', 'Handbook · no time span']));
    expect(select).toHaveValue('w-legal');

    fireEvent.change(select, { target: { value: 'w-docs' } });
    fireEvent.click(screen.getByRole('button', { name: 'Dialog save' }));
    await waitFor(() => expect(sent).toEqual([{ workPackageId: 'w-docs' }]));
  });

  it('resets the package when the goal changes and lists the packages of the new goal', async () => {
    const sent: unknown[] = [];
    serve(sent);
    renderWithProviders(<Planning todo={todoWith({ workPackageId: 'w-legal' })} />);

    await screen.findByRole('option', { name: 'Docs' });
    fireEvent.change(screen.getByRole('combobox', { name: 'Goal' }), { target: { value: 'g2' } });
    const select = screen.getByRole('combobox', { name: 'Work package' });
    expect(select).toHaveValue('');
    await waitFor(() => expect(within(select).getAllByRole('option').map((option) => option.textContent))
      .toEqual(['No work package', 'Other goal · no time span']));

    fireEvent.click(screen.getByRole('button', { name: 'Dialog save' }));
    await waitFor(() => expect(sent).toEqual([{ goalId: 'g2', workPackageId: '' }]));
  });

  it('reads the package an older backend still calls milestoneId, and sends it under that name', async () => {
    const sent: unknown[] = [];
    serve(sent, true);
    renderWithProviders(<Planning todo={todoWith({ milestoneId: 'w-legal' })} />);

    const select = await screen.findByRole('combobox', { name: 'Work package' });
    await waitFor(() => expect(within(select).getAllByRole('option')).toHaveLength(3));
    expect(select).toHaveValue('w-legal');
    fireEvent.change(select, { target: { value: 'w-docs' } });
    fireEvent.click(screen.getByRole('button', { name: 'Dialog save' }));
    await waitFor(() => expect(sent).toEqual([{ milestoneId: 'w-docs' }]));
  });
});

// 68a5e24e: the decision is edited in the planning field of the TODO dialog
describe('TodoPlanningPanel – decision', () => {
  it('saves a decision, refuses it together with a new question, and hides the field for an older backend', async () => {
    const sent: unknown[] = [];
    serve(sent);
    const { unmount } = renderWithProviders(
      <Planning todo={todoWith({ openQuestion: 'Which licence?', decisionState: 'QUESTION', decision: null })} />,
    );

    const field = await screen.findByLabelText('Decision');
    expect(screen.getByText('Answers the open question – saving clears it. Leave empty for no decision.')).toBeInTheDocument();
    fireEvent.change(field, { target: { value: 'MIT' } });
    fireEvent.change(screen.getByLabelText('Open question'), { target: { value: 'And GPL?' } });
    expect(screen.getByRole('alert')).toHaveTextContent('A new question and a decision at once do not go together');
    expect(screen.getByRole('button', { name: 'Dialog save' })).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Open question'), { target: { value: 'Which licence?' } });
    fireEvent.click(screen.getByRole('button', { name: 'Dialog save' }));
    await waitFor(() => expect(sent).toEqual([{ decision: 'MIT' }]));
    unmount();
    expect(screen.queryByRole('button', { name: 'Save planning' })).not.toBeInTheDocument();

    renderWithProviders(<Planning todo={todoWith({ openQuestion: 'Which licence?' })} />);
    await screen.findByRole('combobox', { name: 'Goal' });
    expect(screen.queryByLabelText('Decision')).not.toBeInTheDocument();
  });
});
