import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { GoalOutline } from '../GoalOutline';
import { dayOfIsoDate } from '../timelineLayout';
import type { GoalDetail, GoalMilestone, GoalTodoItem, GoalWorkPackage } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

const endOf = (iso: string) => new Date(`${iso}T23:59:59`).toISOString();
const startOf = (iso: string) => new Date(`${iso}T00:00:00`).toISOString();
const TODAY = dayOfIsoDate('2026-09-27')!;

function item(overrides: Partial<GoalTodoItem>): GoalTodoItem {
  return {
    id: 'x', title: 'X', status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [],
    goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null,
    openQuestion: null, updatedAt: '2026-09-23T00:00:00Z', dueDate: null, workPackageId: null, ...overrides,
  };
}

function wp(id: string, title: string, overrides: Partial<GoalWorkPackage> = {}): GoalWorkPackage {
  return {
    id, goalId: 'g1', title, startDate: null, endDate: null, projectId: 'p1', position: 0, openQuestions: 0, state: 'OPEN',
    total: 0, done: 0, completedAt: null, reopened: false, createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
    ...overrides,
  };
}

function milestone(id: string, title: string, overrides: Partial<GoalMilestone> = {}): GoalMilestone {
  return {
    id, goalId: 'g1', title, dueDate: endOf('2026-10-15'), state: 'OPEN', workPackageIds: [], packagesTotal: 0, packagesDone: 0,
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
    item({ id: 'a', title: 'Privacy findings', workPackageId: 'w-legal', openQuestion: 'Which licence?', ratedBy: 'AI', suggestedEffort: 'high' }),
    item({ id: 'b', title: 'Imprint', workPackageId: 'w-legal', status: 'DONE' }),
    item({ id: 'c', title: 'Banner', workPackageId: 'w-promo', projectId: 'p2', projectName: 'Site' }),
    item({ id: 'd', title: 'Old notes', workPackageId: 'w-done', status: 'DONE' }),
    item({ id: 'e', title: 'Loose end' }),
    item({ id: 'f', title: 'Screenshots', workPackageId: 'w-legal', projectId: 'p2', projectName: 'Site', dueDate: endOf('2026-09-20') }),
  ],
  byProject: [],
  projects: [
    { projectId: 'p1', name: 'AutoPilot', archived: false, position: 0, total: 5, done: 2, openQuestions: 1 },
    { projectId: 'p2', name: 'Site', archived: false, position: 1, total: 1, done: 0, openQuestions: 0 },
    { projectId: 'p3', name: 'Docs', archived: false, position: 2, total: 0, done: 0, openQuestions: 0 },
  ],
  workPackages: [
    wp('w-done', 'Kick-off', { state: 'REACHED', position: 0, total: 1, done: 1 }),
    wp('w-legal', 'Legal', { position: 1, startDate: startOf('2026-10-12'), endDate: endOf('2026-10-15'), total: 3, done: 1 }),
    wp('w-docs', 'Handbook', { position: 2 }),
    wp('w-promo', 'Promotion', { projectId: 'p2' }),
  ],
  milestones: [
    milestone('m-freeze', 'Code freeze', { workPackageIds: ['w-legal', 'w-docs'], packagesTotal: 2, packagesDone: 0 }),
    milestone('m-launch', 'Launch', { dueDate: endOf('2026-10-31') }),
  ],
};

interface Sent { method: string; path: string; body: unknown }

function recordWrites(): Sent[] {
  const sent: Sent[] = [];
  const record = async ({ request }: { request: Request }) => {
    const body = request.method === 'DELETE' ? null : await request.json();
    sent.push({ method: request.method, path: new URL(request.url).pathname, body });
    if (request.method === 'DELETE') return new HttpResponse(null, { status: 204 });
    if (request.url.endsWith('/work-packages/order')) return HttpResponse.json(detail.workPackages);
    if (request.url.endsWith('/projects/order')) return HttpResponse.json(detail);
    if (request.url.includes('/milestones')) return HttpResponse.json({ ...detail.milestones[0], ...(body as object) });
    return HttpResponse.json({ ...detail.workPackages[0], ...(body as object) });
  };
  server.use(
    http.put('/api/v1/goals/g1/work-packages/order', record),
    http.put('/api/v1/goals/g1/projects/order', record),
    http.post('/api/v1/goals/g1/work-packages', record),
    http.put('/api/v1/goals/g1/work-packages/:id', record),
    http.post('/api/v1/goals/g1/milestones', record),
    http.put('/api/v1/goals/g1/milestones/:id', record),
    http.delete('/api/v1/goals/g1/milestones/:id', record),
  );
  return sent;
}

function renderOutline(data: GoalDetail = detail, onOpenTodo = vi.fn()) {
  renderWithProviders(<GoalOutline detail={data} today={TODAY} onOpenTodo={onOpenTodo} />);
  return onOpenTodo;
}

describe('GoalOutline (c60bf914)', () => {
  it('shows a card per project with its work packages in order, each with its time span and TODOs; complete ones are folded', () => {
    renderOutline();

    const autopilot = screen.getByTestId('outline-project-p1');
    const rows = within(autopilot).getAllByTestId(/^outline-package-/).map((row) => row.getAttribute('data-testid'));
    expect(rows).toEqual(['outline-package-w-done', 'outline-package-w-legal', 'outline-package-w-docs']);
    expect(within(autopilot).getByRole('button', { name: /^Kick-off/, expanded: false })).toHaveTextContent('complete');
    expect(within(autopilot).queryByRole('button', { name: /^Open TODO: Old notes/ })).not.toBeInTheDocument();
    const legal = within(autopilot).getByTestId('outline-package-w-legal');
    expect(within(legal).getByRole('button', { name: /^Legal/, expanded: true }).textContent).toMatch(/10\/12\s–\s10\/15/);
    expect(within(legal).getByRole('button', { name: 'Open TODO: Privacy findings (Open)' })).toBeInTheDocument();
    expect(within(legal).getByRole('button', { name: 'Open TODO: Imprint (Done)' })).toHaveClass('line-through');
    // counters: aligned short form, full sentence for screen readers; questions in the human colour
    expect(within(legal).getByTestId('outline-done')).toHaveTextContent('1/31 of 3 done');
    expect(within(legal).getByTestId('outline-questions')).toHaveClass('text-human');
    expect(within(legal).getByTestId('outline-questions')).toHaveTextContent('Open questions: 1');
    // the AI rating is marked in the AI colour
    expect(within(legal).getByRole('img', { name: 'assessed by AI' })).toHaveClass('text-ai');
    expect(within(autopilot).getByTestId('outline-package-w-docs')).toHaveTextContent('no time span');
    expect(within(autopilot).getByTestId('outline-loose-p1')).toHaveTextContent('Loose end');
  });

  it('marks a TODO of another project in a package with ⚠, and shows empty states that act', () => {
    renderOutline();

    const legal = screen.getByTestId('outline-package-w-legal');
    const screenshots = within(legal).getAllByTestId('outline-todo')[2]!;
    expect(within(screenshots).getByRole('img', { name: /belongs to another project than its work package/ })).toHaveClass('text-warning');
    expect(within(legal).getAllByRole('img', { name: /another project/ })).toHaveLength(1);

    const docs = screen.getByTestId('outline-project-p3');
    expect(within(docs).getByText(/No work package yet/)).toBeInTheDocument();
    expect(within(docs).getByRole('button', { name: 'New work package in Docs' })).toBeInTheDocument();
  });

  it('asks to add a project when the goal has none', () => {
    renderOutline({ ...detail, projects: [], workPackages: [], milestones: [], todos: [] });
    expect(screen.getByTestId('outline-no-projects')).toHaveTextContent('This goal has no project yet');
  });

  it('folds and unfolds by click and remembers it per goal', () => {
    renderOutline();

    const toggle = screen.getByRole('button', { name: /^Legal/ });
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('button', { name: /^Open TODO: Privacy findings/ })).not.toBeInTheDocument();
    expect(JSON.parse(localStorage.getItem('mysuricates-goal-outline')!)).toEqual({ g1: { 'wp:w-legal': false } });
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
    // folded away, the packages are skipped: down goes straight to the next project
    fireEvent.keyDown(project, { key: 'ArrowDown' });
    expect(screen.getByRole('button', { name: /^Site/ })).toHaveFocus();
  });

  it('moves a work package within its project and a project within the goal', async () => {
    const sent = recordWrites();
    renderOutline();

    expect(screen.getByRole('button', { name: 'Move work package up: Kick-off' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Move work package down: Kick-off' }));
    await waitFor(() => expect(sent).toEqual([
      { method: 'PUT', path: '/api/v1/goals/g1/work-packages/order', body: { ids: ['w-legal', 'w-done', 'w-docs'] } },
    ]));

    fireEvent.click(screen.getByRole('button', { name: 'Move project up: Site' }));
    await waitFor(() => expect(sent).toContainEqual(
      { method: 'PUT', path: '/api/v1/goals/g1/projects/order', body: { ids: ['p2', 'p1', 'p3'] } },
    ));
  });

  it('adds a work package from its project card, without a time span, and edits one to clear its span', async () => {
    const sent = recordWrites();
    renderOutline();

    fireEvent.click(screen.getByRole('button', { name: 'New work package in Site' }));
    let editor = screen.getByRole('dialog', { name: 'New work package' });
    expect(within(editor).getByLabelText('Project')).toHaveValue('p2');
    fireEvent.change(within(editor).getByLabelText('Title'), { target: { value: 'Launch page' } });
    fireEvent.click(within(editor).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(sent).toEqual([
      { method: 'POST', path: '/api/v1/goals/g1/work-packages', body: { title: 'Launch page', projectId: 'p2' } },
    ]));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'New work package' })).not.toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'Edit work package: Legal' }));
    editor = screen.getByRole('dialog', { name: 'Edit work package' });
    expect(within(editor).getByLabelText('Project')).toBeDisabled();
    expect(within(editor).getByText(/only change while no TODO is assigned/)).toBeInTheDocument();
    expect(within(editor).getByLabelText('Start')).toHaveValue('2026-10-12');
    // only one end set: the form says what is wrong and does not save
    fireEvent.change(within(editor).getByLabelText('End'), { target: { value: '' } });
    expect(within(editor).getByText('Start and end belong together – set both or clear both.')).toBeInTheDocument();
    expect(within(editor).getByRole('button', { name: 'Save' })).toBeDisabled();
    fireEvent.click(within(editor).getByRole('button', { name: 'Clear time span' }));
    fireEvent.click(within(editor).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(sent).toContainEqual(
      { method: 'PUT', path: '/api/v1/goals/g1/work-packages/w-legal', body: { startDate: '', endDate: '' } },
    ));
  });

  it('opens a TODO on click', () => {
    const onOpenTodo = renderOutline();
    fireEvent.click(screen.getByRole('button', { name: 'Open TODO: Loose end (Open)' }));
    expect(onOpenTodo).toHaveBeenCalledWith('e');
  });
});

// c60bf914 (round 2): milestones stand above the projects – dates for the whole goal
describe('GoalOutline — milestones', () => {
  it('lists the milestones with their date and the linked packages, and one without links as ticked by hand', () => {
    renderOutline({
      ...detail,
      milestones: [detail.milestones[0]!, milestone('m-launch', 'Launch', { dueDate: endOf('2026-10-31'), state: 'REACHED' })],
    });

    const block = screen.getByTestId('outline-milestones');
    const freeze = within(block).getByTestId('outline-milestone-m-freeze');
    expect(freeze).toHaveTextContent('Code freeze');
    expect(freeze).toHaveTextContent('10/15/2026');
    expect(freeze).toHaveTextContent('0/2 packages');
    expect(within(freeze).getByText('0 of 2 linked packages complete')).toHaveClass('sr-only');
    expect(within(within(block).getByTestId('outline-milestone-m-launch')).getByRole('img', { name: 'reached' })).toBeInTheDocument();
  });

  it('adds a milestone with a date and linked packages grouped by project', async () => {
    const sent = recordWrites();
    renderOutline();

    fireEvent.click(within(screen.getByTestId('outline-milestones')).getByRole('button', { name: 'New milestone' }));
    const editor = screen.getByRole('dialog', { name: 'New milestone' });
    fireEvent.change(within(editor).getByLabelText('Title'), { target: { value: 'Repo public' } });
    fireEvent.change(within(editor).getByLabelText('Date'), { target: { value: '2026-10-28' } });
    const site = within(editor).getByRole('group', { name: 'Site' });
    fireEvent.click(within(site).getByRole('checkbox', { name: /Promotion/ }));
    fireEvent.click(within(within(editor).getByRole('group', { name: 'AutoPilot' })).getByRole('checkbox', { name: /Legal/ }));
    fireEvent.click(within(editor).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(sent).toEqual([{
      method: 'POST', path: '/api/v1/goals/g1/milestones',
      body: { title: 'Repo public', dueDate: endOf('2026-10-28'), workPackageIds: ['w-promo', 'w-legal'] },
    }]));
  });

  it('edits the links of a milestone and ticks off one without links by hand', async () => {
    const sent = recordWrites();
    renderOutline();

    fireEvent.click(screen.getByRole('button', { name: 'Edit milestone: Code freeze' }));
    let editor = screen.getByRole('dialog', { name: 'Edit milestone' });
    expect(within(editor).getByRole('checkbox', { name: /Legal/ })).toBeChecked();
    // linked: the packages decide, no switch
    expect(within(editor).queryByRole('switch', { name: 'Reached' })).not.toBeInTheDocument();
    expect(within(editor).getByTestId('milestone-packages-progress')).toHaveTextContent('0 of 2 linked packages complete');
    fireEvent.click(within(editor).getByRole('checkbox', { name: /Handbook/ }));
    fireEvent.click(within(editor).getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(sent).toEqual([
      { method: 'PUT', path: '/api/v1/goals/g1/milestones/m-freeze', body: { workPackageIds: ['w-legal'] } },
    ]));
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Edit milestone' })).not.toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'Edit milestone: Launch' }));
    editor = screen.getByRole('dialog', { name: 'Edit milestone' });
    fireEvent.click(within(editor).getByRole('switch', { name: 'Reached' }));
    await waitFor(() => expect(sent).toContainEqual(
      { method: 'PUT', path: '/api/v1/goals/g1/milestones/m-launch', body: { reached: true } },
    ));
  });

  it('deletes a milestone after asking', async () => {
    const sent = recordWrites();
    renderOutline();

    fireEvent.click(screen.getByRole('button', { name: 'Edit milestone: Launch' }));
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Edit milestone' })).getByRole('button', { name: 'Delete' }));
    const question = screen.getByRole('dialog', { name: 'Delete milestone' });
    expect(within(question).getByText('Delete the milestone “Launch”? The work packages stay.')).toBeInTheDocument();
    fireEvent.click(within(question).getByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(sent).toEqual([{ method: 'DELETE', path: '/api/v1/goals/g1/milestones/m-launch', body: null }]));
  });

  it('says that milestones come with the backend restart when the backend is older', () => {
    renderOutline({ ...detail, milestones: [], legacyBackend: true });
    expect(screen.queryByTestId('outline-milestones')).not.toBeInTheDocument();
    expect(screen.getByTestId('outline-milestones-legacy')).toHaveTextContent('Milestones become available after the backend restart.');
  });
});

// c60bf914 (review): a project that only comes in through TODOs is marked and can be added – no packages before
describe('GoalOutline — project not assigned to the goal', () => {
  it('marks it, offers "Add the project" instead of "+ work package", and adds it', async () => {
    const added: unknown[] = [];
    server.use(http.post('/api/v1/goals/g1/projects', async ({ request }) => {
      added.push(await request.json());
      return HttpResponse.json(detail);
    }));
    renderOutline({ ...detail, todos: [...detail.todos, item({ id: 'z', title: 'Stray', projectId: 'p9', projectName: 'Elsewhere' })] });

    const stray = screen.getByTestId('outline-project-p9');
    expect(within(stray).getByText('not assigned')).toHaveClass('text-warning');
    expect(within(stray).queryByRole('button', { name: 'New work package in Elsewhere' })).not.toBeInTheDocument();
    expect(within(stray).queryByRole('button', { name: /^Move project/ })).not.toBeInTheDocument();
    fireEvent.click(within(stray).getByRole('button', { name: 'Add the project' }));
    await waitFor(() => expect(added).toEqual([{ projectId: 'p9' }]));
  });
});
