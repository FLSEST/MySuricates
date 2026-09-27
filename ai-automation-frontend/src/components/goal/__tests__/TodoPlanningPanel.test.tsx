import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { TodoPlanningPanel } from '../TodoPlanningPanel';
import type { GoalMilestone, Todo } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

const goals = [
  { id: 'g1', title: 'Release' },
  { id: 'g2', title: 'Docs' },
];

const ms = (id: string, title: string, projectId: string | null, dueDate: string | null = null): Partial<GoalMilestone> => ({
  id, goalId: 'g1', title, projectId, dueDate, position: 0, openQuestions: 0, state: 'OPEN', total: 0, done: 0,
});

const todo = {
  id: 't1', title: 'Privacy findings', projectId: 'p1', projectName: 'AutoPilot', status: 'OPEN',
  planning: {
    goalId: 'g1', goalTitle: 'Release', goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null,
    ratedBy: null, ratedAt: null, openQuestion: null, milestoneId: 'm-legal',
  },
} as unknown as Todo;

function serve(sent: unknown[]) {
  server.use(
    http.get('/api/v1/goals', () => HttpResponse.json(goals)),
    http.get('/api/v1/goals/:id', ({ params }) => HttpResponse.json({
      goal: { id: params.id, title: 'x', progress: {} },
      todos: [],
      byProject: [],
      projects: [],
      milestones: params.id === 'g1'
        ? [ms('m-legal', 'Legal', 'p1', '2026-10-15T21:59:59Z'), ms('m-promo', 'Promotion', 'p2'), ms('m-docs', 'Handbook', 'p1')]
        : [ms('m-other', 'Other goal', 'p1')],
    })),
    http.patch('/api/v1/todos/t1/planning', async ({ request }) => {
      sent.push(await request.json());
      return HttpResponse.json(todo);
    }),
  );
}

// c60bf914: the planning field picks the sub-goal, filtered to the TODO's project
describe('TodoPlanningPanel – sub-goal', () => {
  it('offers only sub-goals of the TODO\'s project and saves a new one', async () => {
    const sent: unknown[] = [];
    serve(sent);
    renderWithProviders(<TodoPlanningPanel todo={todo} />);

    const select = await screen.findByRole('combobox', { name: 'Sub-goal' });
    await waitFor(() => expect(within(select).getAllByRole('option').map((option) => option.textContent))
      .toEqual(['No sub-goal', 'Legal · Oct 15', 'Handbook · no date']));
    expect(select).toHaveValue('m-legal');

    fireEvent.change(select, { target: { value: 'm-docs' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save planning' }));
    await waitFor(() => expect(sent).toEqual([{ milestoneId: 'm-docs' }]));
  });

  it('resets the sub-goal when the goal changes and lists the sub-goals of the new goal', async () => {
    const sent: unknown[] = [];
    serve(sent);
    renderWithProviders(<TodoPlanningPanel todo={todo} />);

    await screen.findByRole('option', { name: 'Docs' });
    fireEvent.change(screen.getByRole('combobox', { name: 'Goal' }), { target: { value: 'g2' } });
    const select = screen.getByRole('combobox', { name: 'Sub-goal' });
    expect(select).toHaveValue('');
    await waitFor(() => expect(within(select).getAllByRole('option').map((option) => option.textContent))
      .toEqual(['No sub-goal', 'Other goal · no date']));

    fireEvent.click(screen.getByRole('button', { name: 'Save planning' }));
    await waitFor(() => expect(sent).toEqual([{ goalId: 'g2', milestoneId: '' }]));
  });
});
