import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { GoalOutline } from '../GoalOutline';
import { dayOfIsoDate } from '../timelineLayout';
import type { GoalDetail, GoalMilestone, GoalTodoItem } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

const endOf = (iso: string) => new Date(`${iso}T23:59:59`).toISOString();
const TODAY = dayOfIsoDate('2026-09-27')!;

function item(overrides: Partial<GoalTodoItem>): GoalTodoItem {
  return {
    id: 'x', title: 'X', status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [],
    goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null,
    openQuestion: null, updatedAt: '2026-09-23T00:00:00Z', dueDate: null, milestoneId: null, ...overrides,
  };
}

function milestone(id: string, title: string, overrides: Partial<GoalMilestone> = {}): GoalMilestone {
  return {
    id, goalId: 'g1', title, dueDate: null, projectId: 'p1', position: 0, openQuestions: 0, state: 'OPEN', total: 0, done: 0,
    reachedAt: null, reopened: false, createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z', ...overrides,
  };
}

const detail: GoalDetail = {
  goal: {
    id: 'g1', title: 'Release', description: null, status: 'ACTIVE', targetDate: null,
    createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
    progress: { total: 6, open: 4, inProgress: 0, backlog: 0, done: 2, openQuestions: 1, percentDone: 33 },
    guard: { enabled: false, workflowId: null, scheduleId: null, nextRunAt: null },
  },
  todos: [
    item({ id: 'a', title: 'Privacy findings', milestoneId: 'm-legal', openQuestion: 'Which licence?', ratedBy: 'AI', suggestedEffort: 'high' }),
    item({ id: 'b', title: 'Imprint', milestoneId: 'm-legal', status: 'DONE' }),
    item({ id: 'c', title: 'Banner', milestoneId: 'm-promo', projectId: 'p2', projectName: 'Site' }),
    item({ id: 'd', title: 'Old notes', milestoneId: 'm-done', status: 'DONE' }),
    item({ id: 'e', title: 'Loose end' }),
    item({ id: 'f', title: 'Screenshots', milestoneId: 'm-legal', projectId: 'p2', projectName: 'Site', dueDate: endOf('2026-09-20') }),
  ],
  byProject: [],
  projects: [
    { projectId: 'p1', name: 'AutoPilot', archived: false, position: 0, total: 5, done: 2, openQuestions: 1 },
    { projectId: 'p2', name: 'Site', archived: false, position: 1, total: 1, done: 0, openQuestions: 0 },
    { projectId: 'p3', name: 'Docs', archived: false, position: 2, total: 0, done: 0, openQuestions: 0 },
  ],
  milestones: [
    milestone('m-done', 'Kick-off', { state: 'REACHED', position: 0, total: 1, done: 1 }),
    milestone('m-legal', 'Legal', { position: 1, dueDate: endOf('2026-10-15'), total: 3, done: 1 }),
    milestone('m-docs', 'Handbook', { position: 2 }),
    milestone('m-promo', 'Promotion', { projectId: 'p2' }),
  ],
};

interface Sent { method: string; path: string; body: unknown }

function recordWrites(): Sent[] {
  const sent: Sent[] = [];
  const record = async ({ request }: { request: Request }) => {
    const body = await request.json();
    sent.push({ method: request.method, path: new URL(request.url).pathname, body });
    if (request.url.endsWith('/milestones/order')) return HttpResponse.json(detail.milestones);
    if (request.url.endsWith('/projects/order')) return HttpResponse.json(detail);
    return HttpResponse.json({ ...detail.milestones[0], ...(body as object) });
  };
  server.use(
    http.put('/api/v1/goals/g1/milestones/order', record),
    http.put('/api/v1/goals/g1/projects/order', record),
    http.post('/api/v1/goals/g1/milestones', record),
    http.put('/api/v1/goals/g1/milestones/:mid', record),
  );
  return sent;
}

function renderOutline(data: GoalDetail = detail, onOpenTodo = vi.fn()) {
  renderWithProviders(<GoalOutline detail={data} today={TODAY} onOpenTodo={onOpenTodo} />);
  return onOpenTodo;
}

describe('GoalOutline (c60bf914)', () => {
  it('shows a card per project with its sub-goals in order and their TODOs; reached ones are folded', () => {
    renderOutline();

    const autopilot = screen.getByTestId('outline-project-p1');
    const titles = within(autopilot).getAllByTestId(/^outline-subgoal-/).map((row) => row.getAttribute('data-testid'));
    expect(titles).toEqual(['outline-subgoal-m-done', 'outline-subgoal-m-legal', 'outline-subgoal-m-docs']);
    expect(within(autopilot).getByRole('button', { name: /^Kick-off/, expanded: false })).toBeInTheDocument();
    expect(within(autopilot).queryByRole('button', { name: /^Open TODO: Old notes/ })).not.toBeInTheDocument();
    const legal = within(autopilot).getByTestId('outline-subgoal-m-legal');
    expect(within(legal).getByRole('button', { name: /^Legal/, expanded: true })).toHaveTextContent('10/15/2026');
    expect(within(legal).getByRole('button', { name: 'Open TODO: Privacy findings (Open)' })).toBeInTheDocument();
    expect(within(legal).getByRole('button', { name: 'Open TODO: Imprint (Done)' })).toHaveClass('line-through');
    // counters: aligned short form, full sentence for screen readers; questions in the human colour
    expect(within(legal).getByTestId('outline-done')).toHaveTextContent('1/31 of 3 done');
    expect(within(legal).getByTestId('outline-questions')).toHaveClass('text-human');
    expect(within(legal).getByTestId('outline-questions')).toHaveTextContent('Open questions: 1');
    // the AI rating is marked in the AI colour
    expect(within(legal).getByRole('img', { name: 'assessed by AI' })).toHaveClass('text-ai');
    expect(within(autopilot).getByTestId('outline-subgoal-m-docs')).toHaveTextContent('no date');
    expect(within(autopilot).getByTestId('outline-loose-p1')).toHaveTextContent('Loose end');
  });

  it('marks a TODO of another project in a sub-goal with ⚠, and shows empty states that act', () => {
    renderOutline();

    const legal = screen.getByTestId('outline-subgoal-m-legal');
    const screenshots = within(legal).getAllByTestId('outline-todo')[2]!;
    expect(within(screenshots).getByRole('img', { name: /belongs to another project than its sub-goal/ })).toHaveClass('text-warning');
    expect(within(legal).getAllByRole('img', { name: /another project/ })).toHaveLength(1);

    const docs = screen.getByTestId('outline-project-p3');
    expect(within(docs).getByText(/No sub-goal yet/)).toBeInTheDocument();
    expect(within(docs).getByRole('button', { name: 'New sub-goal in Docs' })).toBeInTheDocument();
  });

  it('asks to add a project when the goal has none', () => {
    renderOutline({ ...detail, projects: [], milestones: [], todos: [] });
    expect(screen.getByTestId('outline-no-projects')).toHaveTextContent('This goal has no project yet');
  });

  it('folds and unfolds by click and remembers it per goal', () => {
    renderOutline();

    const toggle = screen.getByRole('button', { name: /^Legal/ });
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('button', { name: /^Open TODO: Privacy findings/ })).not.toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('mysuricates-goal-outline')!)).toEqual({ g1: { 'ms:m-legal': false } });
    fireEvent.click(screen.getByRole('button', { name: /^Kick-off/ }));
    expect(screen.getByRole('button', { name: /^Open TODO: Old notes/ })).toBeInTheDocument();
  });

  it('walks the rows with the arrow keys: down and up move, right unfolds, left folds', () => {
    renderOutline();

    const project = screen.getByRole('button', { name: /^AutoPilot/ });
    act(() => project.focus());
    fireEvent.keyDown(project, { key: 'ArrowDown' });
    const kickoff = screen.getByRole('button', { name: /^Kick-off/ });
    expect(kickoff).toHaveFocus();
    fireEvent.keyDown(kickoff, { key: 'ArrowRight' });
    expect(kickoff).toHaveAttribute('aria-expanded', 'true');
    fireEvent.keyDown(kickoff, { key: 'ArrowLeft' });
    expect(kickoff).toHaveAttribute('aria-expanded', 'false');
    fireEvent.keyDown(kickoff, { key: 'ArrowUp' });
    expect(project).toHaveFocus();
    fireEvent.keyDown(project, { key: 'ArrowLeft' });
    expect(project).toHaveAttribute('aria-expanded', 'false');
    // folded away, the sub-goals are skipped: down goes straight to the next project
    fireEvent.keyDown(project, { key: 'ArrowDown' });
    expect(screen.getByRole('button', { name: /^Site/ })).toHaveFocus();
  });

  it('moves a sub-goal within its project and a project within the goal', async () => {
    const sent = recordWrites();
    renderOutline();

    expect(screen.getByRole('button', { name: 'Move sub-goal up: Kick-off' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Move sub-goal down: Kick-off' }));
    await waitFor(() => expect(sent).toEqual([
      { method: 'PUT', path: '/api/v1/goals/g1/milestones/order', body: { ids: ['m-legal', 'm-done', 'm-docs'] } },
    ]));

    fireEvent.click(screen.getByRole('button', { name: 'Move project up: Site' }));
    await waitFor(() => expect(sent).toContainEqual(
      { method: 'PUT', path: '/api/v1/goals/g1/projects/order', body: { ids: ['p2', 'p1', 'p3'] } },
    ));
  });

  it('adds a sub-goal from its project card, without a date, and edits one to remove its date', async () => {
    const sent = recordWrites();
    renderOutline();

    fireEvent.click(screen.getByRole('button', { name: 'New sub-goal in Site' }));
    let editor = screen.getByRole('dialog', { name: 'New sub-goal' });
    expect(within(editor).getByLabelText('Project')).toHaveValue('p2');
    fireEvent.change(within(editor).getByLabelText('Title'), { target: { value: 'Launch page' } });
    fireEvent.click(within(editor).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(sent).toEqual([
      { method: 'POST', path: '/api/v1/goals/g1/milestones', body: { title: 'Launch page', projectId: 'p2' } },
    ]));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'New sub-goal' })).not.toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'Edit sub-goal: Legal' }));
    editor = screen.getByRole('dialog', { name: 'Edit sub-goal' });
    expect(within(editor).getByLabelText('Project')).toBeDisabled();
    expect(within(editor).getByText(/only change while no TODO is assigned/)).toBeInTheDocument();
    fireEvent.change(within(editor).getByLabelText('Date (optional)'), { target: { value: '' } });
    fireEvent.click(within(editor).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(sent).toContainEqual(
      { method: 'PUT', path: '/api/v1/goals/g1/milestones/m-legal', body: { dueDate: '' } },
    ));
  });

  it('opens a TODO on click', () => {
    const onOpenTodo = renderOutline();
    fireEvent.click(screen.getByRole('button', { name: 'Open TODO: Loose end (Open)' }));
    expect(onOpenTodo).toHaveBeenCalledWith('e');
  });
});
