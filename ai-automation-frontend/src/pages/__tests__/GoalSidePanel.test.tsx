import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { GoalDetail } from '../GoalDetail';
import { ToastContainer } from '../../components/ui/ToastContainer';
import { useToastStore } from '../../stores/toastStore';
import type { GoalDetail as GoalDetailData, GoalTodoItem } from '../../types';

// c60bf914 (Flo): „Beim Anklicken eines Milestones sollten die TODOs und Workflows in der Ansicht rechts
// sein. Und man sollte auch besser zu den TODOs kommen … anklickbar.“ – und: „ziehbar und verschiebbar
// und größenänderbar … bei den tieferen nicht alles erkennbar“.

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1440 });
  Object.defineProperty(window, 'innerHeight', { configurable: true, value: 900 });
});

const endOf = (iso: string) => new Date(`${iso}T23:59:59`).toISOString();

function item(overrides: Partial<GoalTodoItem>): GoalTodoItem {
  return {
    id: 'x', title: 'X', status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [],
    goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null,
    openQuestion: null, updatedAt: '2026-09-23T00:00:00Z', dueDate: null, workPackageId: null,
    decision: null, decidedAt: null, decidedBy: null, decisionState: 'NONE', ...overrides,
  };
}

const detail: GoalDetailData = {
  goal: {
    id: 'g1', title: 'Release', description: null, status: 'ACTIVE', targetDate: null,
    createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
    progress: { total: 3, open: 3, inProgress: 0, backlog: 0, done: 0, openQuestions: 0, decided: 0, percentDone: 0 },
    guard: { enabled: false, workflowId: null, scheduleId: null, nextRunAt: null },
  },
  todos: [
    item({ id: 'a', title: 'Privacy findings', workPackageId: 'w1' }),
    item({ id: 'b', title: 'Licence files', workPackageId: 'w1', dueDate: endOf('2030-10-03') }),
    item({ id: 'c', title: 'Banner', projectId: 'p2', projectName: 'Site', workPackageId: 'w2' }),
  ],
  byProject: [],
  projects: [
    { projectId: 'p1', name: 'AutoPilot', archived: false, position: 0, total: 2, done: 0, openQuestions: 0, decided: 0 },
    { projectId: 'p2', name: 'Site', archived: false, position: 1, total: 1, done: 0, openQuestions: 0, decided: 0 },
  ],
  workPackages: [
    { id: 'w1', goalId: 'g1', title: 'Repo clean', projectId: 'p1', startDate: null, endDate: null, position: 0, state: 'OPEN',
      total: 2, done: 0, openQuestions: 0, decided: 0, completedAt: null, reopened: false, createdAt: '', updatedAt: '' },
    { id: 'w2', goalId: 'g1', title: 'Promotion', projectId: 'p2', startDate: null, endDate: null, position: 0, state: 'OPEN',
      total: 1, done: 0, openQuestions: 0, decided: 0, completedAt: null, reopened: false, createdAt: '', updatedAt: '' },
  ],
  milestones: [{
    id: 'ms1', goalId: 'g1', title: 'Code freeze', dueDate: endOf('2030-10-10'), state: 'OPEN', workPackageIds: ['w1', 'w2'],
    packagesTotal: 2, packagesDone: 0, reachedAt: null, reopened: false, createdAt: '', updatedAt: '',
  }],
};

function Location() {
  const location = useLocation();
  return <p data-testid="location">{location.search}</p>;
}

function serveGoal() {
  server.use(
    http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)),
    http.get('/api/v1/goals/g1/workflows', () => HttpResponse.json([
      { id: 'a1', goalId: 'g1', workPackageId: 'w1', workflowId: 'wf-fix', workflowName: 'Fix bot', workflowProjectId: 'p1', role: 'WORKER', guard: false },
    ])),
    http.get('/api/v1/dashboard/workflow-results', () => HttpResponse.json([])),
    http.get('/api/v1/todos/:id', ({ params }) => HttpResponse.json({
      id: params.id, title: 'Privacy findings', description: 'Full text', status: 'OPEN', priority: 'MEDIUM', tags: [],
      projectId: 'p1', projectName: 'AutoPilot', source: 'USER', createdAt: '', updatedAt: '',
    })),
  );
}

function renderGoal(route = '/goals/g1') {
  serveGoal();
  return renderWithProviders(
    <>
      <Routes><Route path="/goals/:goalId" element={<GoalDetail />} /></Routes>
      <Location />
    </>,
    { route },
  );
}

const panel = () => screen.getByTestId('goal-side-panel');

describe('GoalSidePanel – opening and closing', () => {
  it('opens a work package from the outline, keeps it in the URL, closes on Escape and gives the focus back', async () => {
    renderGoal();

    const title = await screen.findByRole('button', { name: 'Open work package: Repo clean' });
    fireEvent.click(title);
    expect(screen.getByTestId('location')).toHaveTextContent('?item=workpackage:w1');
    expect(panel()).toHaveAttribute('data-kind', 'workpackage');
    expect(within(panel()).getByRole('heading', { name: 'Details: Repo clean' })).toHaveFocus();
    expect(title).toHaveAttribute('aria-current', 'true');
    // its TODOs, workflows and milestones
    expect(within(screen.getByTestId('panel-todos')).getAllByTestId('panel-todo').map((row) => row.textContent))
      .toEqual(['Privacy findings', expect.stringContaining('Licence files')]);
    expect(within(screen.getByTestId('panel-workflows')).getByRole('button', { name: 'Start: Fix bot' })).toBeInTheDocument();
    expect(within(screen.getByTestId('panel-milestones')).getByRole('button', { name: 'Open milestone: Code freeze' })).toBeInTheDocument();

    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(screen.queryByTestId('goal-side-panel')).not.toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent(/^$/);
    expect(title).toHaveFocus();
  });

  it('opens a milestone with its linked packages by project, their TODOs and workflows; a package inside switches, × closes', async () => {
    renderGoal();

    const trigger = await screen.findByRole('button', { name: 'Open milestone: Code freeze' });
    fireEvent.click(trigger);
    expect(screen.getByTestId('location')).toHaveTextContent('?item=milestone:ms1');
    const linked = screen.getByTestId('panel-linked-packages');
    expect(within(linked).getByRole('group', { name: 'AutoPilot' })).toHaveTextContent('Repo clean');
    expect(within(linked).getByRole('group', { name: 'Site' })).toHaveTextContent('Promotion');
    expect(within(screen.getByTestId('panel-package-w1')).getByRole('button', { name: 'Start: Fix bot' })).toBeInTheDocument();
    expect(within(screen.getByTestId('panel-package-w2')).getAllByTestId('panel-todo')).toHaveLength(1);
    expect(panel()).toHaveTextContent('0 of 2 linked packages complete');

    fireEvent.click(within(linked).getByRole('button', { name: 'Open work package: Promotion' }));
    expect(screen.getByTestId('location')).toHaveTextContent('?item=workpackage:w2');
    fireEvent.click(within(panel()).getByRole('button', { name: 'Close the side panel' }));
    // back to the milestone line that opened the panel first
    expect(trigger).toHaveFocus();
  });

  it('opens straight from the URL, as after a reload', async () => {
    renderGoal('/goals/g1?item=milestone:ms1');
    expect(await screen.findByTestId('goal-side-panel')).toHaveAttribute('data-kind', 'milestone');
    expect(within(panel()).getByRole('heading', { name: 'Details: Code freeze' })).toBeInTheDocument();
  });

  it('ignores an unknown item in the URL', async () => {
    renderGoal('/goals/g1?item=workpackage:gone');
    await screen.findByRole('heading', { name: 'Release' });
    expect(screen.queryByTestId('goal-side-panel')).not.toBeInTheDocument();
  });

  it('opens a TODO from the panel in the TODO dialog', async () => {
    renderGoal('/goals/g1?item=workpackage:w1');
    fireEvent.click(await within(await screen.findByTestId('panel-todos')).findByRole('button', { name: 'Open TODO: Privacy findings (Open)' }));
    expect(await screen.findByDisplayValue('Full text')).toBeInTheDocument();
    // Escape belongs to the dialog – the panel stays
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
    expect(screen.getByTestId('goal-side-panel')).toBeInTheDocument();
  });
});

describe('GoalSidePanel – width, window and memory', () => {
  it('changes its width at the left handle by keyboard, within bounds, and remembers it', async () => {
    renderGoal('/goals/g1?item=workpackage:w1');

    const handle = await screen.findByRole('separator', { name: 'Width of the side panel – arrow keys' });
    expect(handle).toHaveAttribute('aria-valuenow', '384');
    // never cut off below: exactly down to the bottom of the view (900 px), scrolling within itself
    expect(panel()).toHaveStyle({ height: '868px' });
    expect(within(panel()).getByTestId('goal-side-panel-scroll')).toHaveClass('overflow-y-auto');
    vi.spyOn(panel(), 'getBoundingClientRect').mockReturnValue({ top: 200 } as DOMRect);
    act(() => { window.dispatchEvent(new Event('scroll')); });
    expect(panel()).toHaveStyle({ height: '684px' });
    fireEvent.keyDown(handle, { key: 'ArrowLeft' });
    fireEvent.keyDown(handle, { key: 'ArrowLeft', shiftKey: true });
    expect(handle).toHaveAttribute('aria-valuenow', '464');
    expect(panel()).toHaveStyle({ width: '464px' });
    fireEvent.keyDown(handle, { key: 'End' });
    // at most 720 px and never more than 60 % of the page (1440 px)
    expect(handle).toHaveAttribute('aria-valuenow', '720');
    fireEvent.keyDown(handle, { key: 'Home' });
    expect(handle).toHaveAttribute('aria-valuenow', '300');
    expect(JSON.parse(localStorage.getItem('mysuricates-goal-panel')!)).toMatchObject({ mode: 'docked', width: 300 });
  });

  it('detaches into a window that moves by its title bar and resizes at its corner, stays in view and docks again', async () => {
    renderGoal('/goals/g1?item=workpackage:w1');

    fireEvent.click(await screen.findByRole('button', { name: 'Detach as a window' }));
    expect(panel()).toHaveAttribute('data-mode', 'floating');
    const bar = screen.getByTestId('goal-side-panel-titlebar');
    const { left, top } = panel().style;
    fireEvent.keyDown(bar, { key: 'ArrowLeft', shiftKey: true });
    fireEvent.keyDown(bar, { key: 'ArrowUp' });
    expect(parseInt(panel().style.left, 10)).toBe(parseInt(left, 10) - 64);
    expect(parseInt(panel().style.top, 10)).toBe(parseInt(top, 10) - 16);

    // by mouse: dragged far beyond the edge, it stops inside the view
    fireEvent.pointerDown(bar, { button: 0, clientX: 500, clientY: 100 });
    fireEvent.pointerMove(window, { clientX: -2000, clientY: 5000 });
    fireEvent.pointerUp(window);
    expect(panel().style.left).toBe('8px');
    expect(parseInt(panel().style.top, 10) + parseInt(panel().style.height, 10)).toBeLessThanOrEqual(900 - 8);

    const corner = screen.getByRole('button', { name: 'Resize the window – arrow keys' });
    const width = parseInt(panel().style.width, 10);
    fireEvent.keyDown(corner, { key: 'ArrowRight' });
    expect(parseInt(panel().style.width, 10)).toBe(width + 16);

    // the browser window shrinks: the panel comes back into view
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 600 });
    Object.defineProperty(window, 'innerHeight', { configurable: true, value: 500 });
    act(() => { window.dispatchEvent(new Event('resize')); });
    expect(parseInt(panel().style.left, 10) + parseInt(panel().style.width, 10)).toBeLessThanOrEqual(600 - 8);
    expect(parseInt(panel().style.top, 10) + parseInt(panel().style.height, 10)).toBeLessThanOrEqual(500 - 8);
    expect(JSON.parse(localStorage.getItem('mysuricates-goal-panel')!)).toMatchObject({ mode: 'floating' });

    fireEvent.click(screen.getByRole('button', { name: 'Dock' }));
    expect(panel()).toHaveAttribute('data-mode', 'docked');
  });

  it('opens as a window again after a reload when it was detached', async () => {
    localStorage.setItem('mysuricates-goal-panel', JSON.stringify({ mode: 'floating', width: 400, rect: { x: 3000, y: 50, width: 500, height: 600 } }));
    renderGoal('/goals/g1?item=milestone:ms1');
    expect(await screen.findByTestId('goal-side-panel')).toHaveAttribute('data-mode', 'floating');
    // a remembered place outside the view is pulled back in
    expect(parseInt(panel().style.left, 10) + 500).toBeLessThanOrEqual(1440 - 8);
  });

  it('is a sheet over the content below 1024 px, without detaching', async () => {
    const original = window.matchMedia;
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: query.includes('max-width: 1023px'), media: query, onchange: null,
      addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
    }));
    try {
      renderGoal('/goals/g1?item=workpackage:w1');
      expect(await screen.findByTestId('goal-side-panel')).toHaveAttribute('data-mode', 'sheet');
      expect(screen.queryByRole('button', { name: 'Detach as a window' })).not.toBeInTheDocument();
    } finally {
      window.matchMedia = original;
    }
  });
});

describe('TODOs are clickable in every view', () => {
  it('opens the TODO dialog from the list', async () => {
    localStorage.setItem('mysuricates-goal-view-v2', 'list');
    renderGoal();
    const [first] = await screen.findAllByTestId('goal-next-item');
    fireEvent.click(within(first!).getByRole('button', { name: 'Open TODO: Privacy findings' }));
    expect(await screen.findByDisplayValue('Full text')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument());
  });
});

// Review c60bf914: the selection replaces its history entry – Back leaves the goal page instead of
// stepping through every opened item; a small sheet is modal; no drag listener outlives the panel
describe('GoalSidePanel – history, modal sheet, clean-up', () => {
  function Back() {
    const navigate = useNavigate();
    return <button type="button" onClick={() => navigate(-1)}>Back</button>;
  }
  function Where() {
    const location = useLocation();
    return <p data-testid="where">{location.pathname}{location.search}</p>;
  }

  it('does not add a history entry per selection: one Back leaves the goal page', async () => {
    serveGoal();
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter initialEntries={['/start', '/goals/g1']} initialIndex={1}>
          <Routes>
            <Route path="/goals/:goalId" element={<GoalDetail />} />
            <Route path="/start" element={<p>Start page</p>} />
          </Routes>
          <Where />
          <Back />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    fireEvent.click(await screen.findByRole('button', { name: 'Open work package: Repo clean' }));
    expect(screen.getByTestId('where')).toHaveTextContent('/goals/g1?item=workpackage:w1');
    fireEvent.click(screen.getAllByRole('button', { name: 'Open milestone: Code freeze' })[0]!);
    expect(screen.getByTestId('where')).toHaveTextContent('/goals/g1?item=milestone:ms1');
    fireEvent.click(within(screen.getByTestId('goal-side-panel')).getByRole('button', { name: 'Close the side panel' }));
    expect(screen.getByTestId('where')).toHaveTextContent('/goals/g1');
    fireEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(screen.getByTestId('where')).toHaveTextContent('/start');
    expect(screen.getByText('Start page')).toBeInTheDocument();
  });

  it('makes the small-screen sheet a modal dialog: the page behind is inert, Tab stays inside, a TODO dialog on top still works', async () => {
    const original = window.matchMedia;
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: query.includes('max-width: 1023px'), media: query, onchange: null,
      addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
    }));
    try {
      const { container } = renderGoal('/goals/g1?item=workpackage:w1');
      const sheet = await screen.findByTestId('goal-side-panel');
      expect(sheet).toHaveAttribute('role', 'dialog');
      expect(sheet).toHaveAttribute('aria-modal', 'true');
      expect(container).toHaveAttribute('inert');
      expect(container.contains(sheet)).toBe(false);

      const buttons = within(sheet).getAllByRole('button');
      const last = buttons.at(-1)!;
      act(() => last.focus());
      fireEvent.keyDown(last, { key: 'Tab' });
      expect(buttons[0]).toHaveFocus();
      fireEvent.keyDown(buttons[0]!, { key: 'Tab', shiftKey: true });
      expect(last).toHaveFocus();

      // the TODO dialog opens in the page – while it is open, the page is usable again
      fireEvent.click(within(sheet).getByRole('button', { name: 'Open TODO: Privacy findings (Open)' }));
      expect(await screen.findByDisplayValue('Full text')).toBeInTheDocument();
      await waitFor(() => expect(container).not.toHaveAttribute('inert'));

      fireEvent.keyDown(screen.getByDisplayValue('Full text'), { key: 'Escape' });
      await waitFor(() => expect(screen.queryByDisplayValue('Full text')).not.toBeInTheDocument());
      await waitFor(() => expect(container).toHaveAttribute('inert'));
      fireEvent.click(within(screen.getByTestId('goal-side-panel')).getByRole('button', { name: 'Close the side panel' }));
      expect(container).not.toHaveAttribute('inert');
    } finally {
      window.matchMedia = original;
    }
  });

  // d86f27b2: the sheet is a modal dialog itself, so the old check "is a dialog on top?" always found it and
  // Escape did nothing below 1024 px
  it('closes the small-screen sheet with Escape – a dialog on top of it first – and unlocks the page scroll', async () => {
    const original = window.matchMedia;
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: query.includes('max-width: 1023px'), media: query, onchange: null,
      addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
    }));
    try {
      renderGoal('/goals/g1?item=workpackage:w1');
      const sheet = await screen.findByTestId('goal-side-panel');
      expect(document.body.style.overflow).toBe('hidden');

      fireEvent.click(within(sheet).getByRole('button', { name: 'Open TODO: Privacy findings (Open)' }));
      expect(await screen.findByDisplayValue('Full text')).toBeInTheDocument();
      fireEvent.keyDown(screen.getByDisplayValue('Full text'), { key: 'Escape' });
      await waitFor(() => expect(screen.queryByDisplayValue('Full text')).not.toBeInTheDocument());
      // only the dialog closed; the sheet keeps the page locked
      expect(screen.getByTestId('goal-side-panel')).toBeInTheDocument();
      expect(document.body.style.overflow).toBe('hidden');

      fireEvent.keyDown(document.body, { key: 'Escape' });
      expect(screen.queryByTestId('goal-side-panel')).not.toBeInTheDocument();
      expect(screen.getByTestId('location')).toHaveTextContent(/^$/);
      expect(document.body.style.overflow).toBe('');
    } finally {
      window.matchMedia = original;
    }
  });

  it('stays open when something inside already used Escape – an inline field, a keyboard drag', async () => {
    renderGoal('/goals/g1?item=workpackage:w1');
    await screen.findByTestId('goal-side-panel');
    // dnd-kit cancels a keyboard drag in a document listener and prevents the default
    const usedByDrag = (event: KeyboardEvent) => event.preventDefault();
    document.addEventListener('keydown', usedByDrag);
    try {
      fireEvent.keyDown(document.body, { key: 'Escape' });
    } finally {
      document.removeEventListener('keydown', usedByDrag);
    }
    expect(screen.getByTestId('goal-side-panel')).toBeInTheDocument();
  });

  // Review c60bf914: toasts are a portal of their own – under the modal sheet their buttons stay usable
  it('keeps toasts clickable while the sheet is open', async () => {
    const original = window.matchMedia;
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: query.includes('max-width: 1023px'), media: query, onchange: null,
      addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
    }));
    const undo = vi.fn();
    try {
      serveGoal();
      const { container } = renderWithProviders(
        <>
          <Routes><Route path="/goals/:goalId" element={<GoalDetail />} /></Routes>
          <ToastContainer />
        </>,
        { route: '/goals/g1?item=workpackage:w1' },
      );
      await screen.findByTestId('goal-side-panel');
      act(() => { useToastStore.getState().addToast('success', 'Assigned', { label: 'Undo', onClick: undo }); });
      const toast = await screen.findByTestId('toast');
      expect(container).toHaveAttribute('inert');
      expect(toast.closest('[inert]')).toBeNull();
      fireEvent.click(within(toast).getByRole('button', { name: 'Undo' }));
      expect(undo).toHaveBeenCalled();
    } finally {
      window.matchMedia = original;
    }
  });

  it('removes its drag listeners when it closes in the middle of a drag', async () => {
    renderGoal('/goals/g1?item=workpackage:w1');
    fireEvent.click(await screen.findByRole('button', { name: 'Detach as a window' }));
    const added = vi.spyOn(window, 'addEventListener');
    const removed = vi.spyOn(window, 'removeEventListener');
    fireEvent.pointerDown(screen.getByTestId('goal-side-panel-titlebar'), { button: 0, clientX: 100, clientY: 100 });
    expect(added.mock.calls.filter(([type]) => type === 'pointermove')).toHaveLength(1);
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(screen.queryByTestId('goal-side-panel')).not.toBeInTheDocument();
    expect(removed.mock.calls.filter(([type]) => type === 'pointermove')).toHaveLength(1);
    added.mockRestore();
    removed.mockRestore();
  });
});
