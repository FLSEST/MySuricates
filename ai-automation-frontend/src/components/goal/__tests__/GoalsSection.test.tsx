import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { GoalsSection } from '../GoalsSection';
import type { Goal } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

const goal: Goal = {
  id: 'g1', title: 'Open-Source-Launch', description: null, status: 'ACTIVE', targetDate: null,
  createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
  progress: { total: 5, open: 2, inProgress: 1, backlog: 0, done: 2, openQuestions: 3, percentDone: 40 },
  guard: { enabled: false, workflowId: null, scheduleId: null, nextRunAt: null },
};

describe('GoalsSection', () => {
  it('shows each goal with its progress and open questions', async () => {
    server.use(http.get('/api/v1/goals', () => HttpResponse.json([goal])));
    renderWithProviders(<GoalsSection />);

    expect(await screen.findByRole('button', { name: 'Open-Source-Launch' })).toBeInTheDocument();
    expect(screen.getByText('2 of 5 done')).toBeInTheDocument();
    expect(screen.getByText('Open questions: 3')).toBeInTheDocument();
  });

  // c60bf914: the card names the next milestone of the goal
  it('shows the next milestone on the card, and nothing for an older backend without it', async () => {
    server.use(http.get('/api/v1/goals', () => HttpResponse.json([
      { ...goal, nextMilestone: { id: 'm1', title: 'Code-Freeze', dueDate: new Date(2026, 9, 15, 23, 59, 59).toISOString(), state: 'DUE_SOON' } },
      { ...goal, id: 'g2', title: 'Docs' },
    ])));
    renderWithProviders(<GoalsSection />);

    const card = await screen.findByRole('button', { name: 'Open-Source-Launch' });
    const next = within(card).getByTestId('goal-card-milestone');
    expect(next).toHaveTextContent('Code-Freeze');
    expect(next).toHaveTextContent('10/15');
    expect(next).toHaveAttribute('title', 'Next milestone: Code-Freeze, 10/15/2026');
    expect(within(screen.getByRole('button', { name: 'Docs' })).queryByTestId('goal-card-milestone')).not.toBeInTheDocument();
  });

  it('explains what a goal is while there is none, and creates one with title and date', async () => {
    let body: unknown = null;
    server.use(
      http.get('/api/v1/goals', () => HttpResponse.json([])),
      http.post('/api/v1/goals', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...goal, id: 'new' }, { status: 201 });
      }),
    );
    renderWithProviders(<GoalsSection />);

    expect(await screen.findByText(/A goal collects TODOs from several projects/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /New goal/ }));
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: '  Launch  ' } });
    fireEvent.change(screen.getByLabelText('Target date'), { target: { value: '2026-10-15' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(body).toEqual({ title: 'Launch', targetDate: '2026-10-15' }));
  });

  it('stays compact: only active goals, at most four, and a link to all goals', async () => {
    const many = Array.from({ length: 6 }, (_, i) => ({ ...goal, id: `g${i}`, title: `Goal ${i}` }));
    server.use(http.get('/api/v1/goals', () => HttpResponse.json([...many, { ...goal, id: 'done', title: 'Reached one', status: 'DONE' }])));
    renderWithProviders(<GoalsSection />);

    expect(await screen.findByRole('button', { name: 'Goal 0' })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /^Goal \d$/ })).toHaveLength(4);
    expect(screen.queryByRole('button', { name: 'Reached one' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: /All goals/ })).toHaveAttribute('href', '/goals');
  });

  // c60bf914: the cockpit filters goals by project – remembered, "All projects" by default
  describe('project filter', () => {
    const projects = [{ id: 'p1', name: 'AutoPilot' }, { id: 'p2', name: 'Site' }, { id: 'p3', name: 'Old', archivedAt: '2026-01-01' }];
    const launch = { ...goal, projects: [{ id: 'p1', name: 'AutoPilot' }, { id: 'p2', name: 'Site' }] };
    const docs = { ...goal, id: 'g2', title: 'Docs overhaul', projects: [{ id: 'p1', name: 'AutoPilot' }] };

    function serve(asked: (string | null)[], answer: (projectId: string | null) => unknown[]) {
      server.use(
        http.get('/api/v1/projects', () => HttpResponse.json(projects)),
        http.get('/api/v1/goals', ({ request }) => {
          const projectId = new URL(request.url).searchParams.get('projectId');
          asked.push(projectId);
          return HttpResponse.json(answer(projectId));
        }),
      );
    }

    it('shows all projects by default, asks the server for one project and remembers the choice', async () => {
      const asked: (string | null)[] = [];
      serve(asked, (projectId) => (projectId === 'p2' ? [launch] : [launch, docs]));
      renderWithProviders(<GoalsSection />);

      const filter = await screen.findByRole('combobox', { name: 'Project' });
      expect(filter).toHaveValue('');
      expect(within(filter).getAllByRole('option').map((option) => option.textContent)).toEqual(['All projects', 'AutoPilot', 'Site']);
      expect(await screen.findByRole('button', { name: 'Docs overhaul' })).toBeInTheDocument();
      expect(asked[0]).toBeNull();

      fireEvent.change(filter, { target: { value: 'p2' } });
      await waitFor(() => expect(asked).toContain('p2'));
      expect(await screen.findByRole('button', { name: 'Open-Source-Launch' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Docs overhaul' })).not.toBeInTheDocument();
      expect(asked).toContain('p2');
      expect(localStorage.getItem('mysuricates-goals-project-filter')).toBe('p2');
      // the card carries its projects as chips
      expect(within(screen.getByTestId('goal-card-projects')).getByText('Site')).toBeInTheDocument();
    });

    it('starts with the remembered project and filters on its own when an older backend ignores the parameter', async () => {
      localStorage.setItem('mysuricates-goals-project-filter', 'p2');
      const asked: (string | null)[] = [];
      serve(asked, () => [launch, docs, { ...goal, id: 'g3', title: 'Unknown projects' }]);
      renderWithProviders(<GoalsSection />);

      expect(await screen.findByRole('button', { name: 'Open-Source-Launch' })).toBeInTheDocument();
      expect(screen.getByRole('combobox', { name: 'Project' })).toHaveValue('p2');
      expect(screen.queryByRole('button', { name: 'Docs overhaul' })).not.toBeInTheDocument();
      // a goal from a backend without projects stays – it cannot be told apart
      expect(screen.getByRole('button', { name: 'Unknown projects' })).toBeInTheDocument();
    });

    it('creates a goal in the filtered project', async () => {
      localStorage.setItem('mysuricates-goals-project-filter', 'p1');
      let body: unknown = null;
      serve([], () => []);
      server.use(http.post('/api/v1/goals', async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ ...goal, id: 'new' }, { status: 201 });
      }));
      renderWithProviders(<GoalsSection />);

      fireEvent.click(await screen.findByRole('button', { name: /New goal/ }));
      fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Launch' } });
      await waitFor(() => expect(screen.getByRole('combobox', { name: 'Project (optional)' })).toHaveValue('p1'));
      fireEvent.click(screen.getByRole('button', { name: 'Save' }));
      await waitFor(() => expect(body).toEqual({ title: 'Launch', projectIds: ['p1'] }));
    });
  });
});
