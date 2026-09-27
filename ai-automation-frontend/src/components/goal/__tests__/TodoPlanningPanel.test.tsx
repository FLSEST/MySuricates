import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { TodoPlanningPanel } from '../TodoPlanningPanel';
import type { GoalWorkPackage, Todo } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
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

/** `legacy`: das Ziel antwortet wie ein Backend vor c60bf914 – ohne `workPackages`, die Pakete heißen „milestones“. */
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

// c60bf914: the planning field picks the work package, filtered to the TODO's project
describe('TodoPlanningPanel – work package', () => {
  it('offers only packages of the TODO\'s project with their span and saves a new one as workPackageId', async () => {
    const sent: unknown[] = [];
    serve(sent);
    renderWithProviders(<TodoPlanningPanel todo={todoWith({ workPackageId: 'w-legal' })} />);

    const select = await screen.findByRole('combobox', { name: 'Work package' });
    await waitFor(() => expect(within(select).getAllByRole('option').map((option) => option.textContent?.replace(/\s/g, ' ')))
      .toEqual(['No work package', 'Legal · 10/12 – 10/15', 'Handbook · no time span']));
    expect(select).toHaveValue('w-legal');

    fireEvent.change(select, { target: { value: 'w-docs' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save planning' }));
    await waitFor(() => expect(sent).toEqual([{ workPackageId: 'w-docs' }]));
  });

  it('resets the package when the goal changes and lists the packages of the new goal', async () => {
    const sent: unknown[] = [];
    serve(sent);
    renderWithProviders(<TodoPlanningPanel todo={todoWith({ workPackageId: 'w-legal' })} />);

    await screen.findByRole('option', { name: 'Docs' });
    fireEvent.change(screen.getByRole('combobox', { name: 'Goal' }), { target: { value: 'g2' } });
    const select = screen.getByRole('combobox', { name: 'Work package' });
    expect(select).toHaveValue('');
    await waitFor(() => expect(within(select).getAllByRole('option').map((option) => option.textContent))
      .toEqual(['No work package', 'Other goal · no time span']));

    fireEvent.click(screen.getByRole('button', { name: 'Save planning' }));
    await waitFor(() => expect(sent).toEqual([{ goalId: 'g2', workPackageId: '' }]));
  });

  it('reads the package an older backend still calls milestoneId, and sends it under that name', async () => {
    const sent: unknown[] = [];
    serve(sent, true);
    renderWithProviders(<TodoPlanningPanel todo={todoWith({ milestoneId: 'w-legal' })} />);

    const select = await screen.findByRole('combobox', { name: 'Work package' });
    await waitFor(() => expect(within(select).getAllByRole('option')).toHaveLength(3));
    expect(select).toHaveValue('w-legal');
    fireEvent.change(select, { target: { value: 'w-docs' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save planning' }));
    await waitFor(() => expect(sent).toEqual([{ milestoneId: 'w-docs' }]));
  });
});
