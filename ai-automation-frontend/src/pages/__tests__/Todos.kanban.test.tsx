import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, within, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useSettingsStore } from '../../stores/settingsStore';
import { Todos } from '../Todos';
import type { Todo, TodoStatus } from '../../types';

const BASE = '/api/v1';

function makeTodos(status: TodoStatus, count: number, prefix: string): Todo[] {
  return Array.from({ length: count }, (_, i) => ({
    id: `${prefix}-${i}`,
    title: `${prefix} todo ${i}`,
    description: null,
    status,
    priority: 'MEDIUM',
    tags: [],
    projectId: null,
    projectName: null,
    linkedWorkflowId: null,
    linkedWorkflowName: null,
    source: 'USER',
    sourceAgent: null,
    workflowRunId: null,
    referenceType: null,
    referenceId: null,
    fixAttempts: 0,
    lastFixRunId: null,
    resolutionState: 'IDLE',
    dueDate: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  } as Todo));
}

/** A DONE-dominated backlog: 141 open, 1 700 done — the case the mixed page starved. */
const PAGES: Record<TodoStatus, { content: Todo[]; totalElements: number }> = {
  OPEN: { content: makeTodos('OPEN', 7, 'open'), totalElements: 141 },
  IN_PROGRESS: { content: makeTodos('IN_PROGRESS', 2, 'wip'), totalElements: 2 },
  DONE: { content: makeTodos('DONE', 50, 'done'), totalElements: 1700 },
  BACKLOG: { content: [], totalElements: 0 },
  ARCHIVED: { content: [], totalElements: 0 },
};

let requests: URL[] = [];

function mockColumnEndpoints() {
  server.use(
    http.get(`${BASE}/todos/count`, () =>
      HttpResponse.json({ open: 141, inProgress: 2, done: 1700, backlog: 0, archived: 5 }),
    ),
    http.get(`${BASE}/todos`, ({ request }) => {
      const url = new URL(request.url);
      requests.push(url);
      const statuses = url.searchParams.getAll('status');
      const size = Number(url.searchParams.get('size') ?? '30');
      const page =
        statuses.length === 1
          ? PAGES[statuses[0] as TodoStatus]
          : {
              // A mixed page is sorted by recency → almost only DONE
              content: [...PAGES.DONE.content, ...PAGES.OPEN.content].slice(0, size),
              totalElements: 1843,
            };
      const content = page.content.slice(0, size);
      return HttpResponse.json({
        content,
        page: 0,
        size,
        totalElements: page.totalElements,
        totalPages: Math.max(1, Math.ceil(page.totalElements / size)),
      });
    }),
    http.get(`${BASE}/integrations`, () => HttpResponse.json([])),
    http.get(`${BASE}/todo-triggers`, () => HttpResponse.json([])),
  );
}

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
beforeEach(() => {
  requests = [];
  localStorage.clear();
  useSettingsStore.setState({ listPageSize: 30, kanbanColumnSize: 50 });
});
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

function renderBoard(statusFilter = 'ALL') {
  localStorage.setItem('todos-view-mode', JSON.stringify('board'));
  localStorage.setItem('todos-status-filter', JSON.stringify(statusFilter));
  mockColumnEndpoints();
  return renderWithProviders(<Todos />, { route: '/todos' });
}

describe('Todos — kanban queries one page per column', () => {
  it('fills the OPEN column from its own query, not from a mixed page', async () => {
    renderBoard();

    const column = await screen.findByTestId('kanban-column-OPEN');
    await waitFor(() => {
      expect(within(column).getAllByText(/^open todo \d+$/)).toHaveLength(7);
    });
    // no DONE card leaked into the OPEN column
    expect(within(column).queryByText(/^done todo/)).toBeNull();
  });

  it('shows the truncation hint per column (7 of 141, 50 of 1700)', async () => {
    renderBoard();

    const openColumn = await screen.findByTestId('kanban-column-OPEN');
    await waitFor(() => {
      expect(within(openColumn).getByText(/7 of 141/)).toBeInTheDocument();
    });

    const doneColumn = await screen.findByTestId('kanban-column-DONE');
    await waitFor(() => {
      expect(within(doneColumn).getAllByText(/^done todo \d+$/)).toHaveLength(50);
    });
    expect(within(doneColumn).getByText(/50 of 1700/)).toBeInTheDocument();
  });

  it('asks the API once per column with its own status parameter', async () => {
    renderBoard();

    await screen.findByTestId('kanban-column-BACKLOG');
    await waitFor(() => {
      const statuses = requests.map((url) => url.searchParams.getAll('status').join(','));
      expect(statuses).toContain('OPEN');
      expect(statuses).toContain('IN_PROGRESS');
      expect(statuses).toContain('DONE');
      expect(statuses).toContain('BACKLOG');
    });
    // Never a mixed page in board mode
    expect(requests.every((url) => url.searchParams.getAll('status').length === 1)).toBe(true);
    // Column size comes from the settings store
    expect(requests.every((url) => url.searchParams.get('size') === '50')).toBe(true);
  });

  it('uses the configured column size from the settings store', async () => {
    useSettingsStore.setState({ kanbanColumnSize: 20 });
    renderBoard();

    await screen.findByTestId('kanban-column-OPEN');
    await waitFor(() => expect(requests.length).toBeGreaterThan(0));
    expect(requests.every((url) => url.searchParams.get('size') === '20')).toBe(true);
  });

  it('renders only the columns the status filter selects', async () => {
    renderBoard('OPEN');

    await screen.findByTestId('kanban-column-OPEN');
    expect(screen.queryByTestId('kanban-column-DONE')).toBeNull();
    expect(screen.queryByTestId('kanban-column-BACKLOG')).toBeNull();
  });
});

describe('Todos — default status filter', () => {
  it('defaults to OPEN + IN_PROGRESS for a fresh browser', async () => {
    mockColumnEndpoints();
    renderWithProviders(<Todos />, { route: '/todos' });

    await waitFor(() => expect(requests.length).toBeGreaterThan(0));
    const listRequest = requests.find((url) => url.searchParams.getAll('status').length > 0);
    expect(listRequest?.searchParams.getAll('status')).toEqual(['OPEN', 'IN_PROGRESS']);
  });

  it('keeps a persisted ALL selection', async () => {
    localStorage.setItem('todos-status-filter', JSON.stringify('ALL'));
    mockColumnEndpoints();
    renderWithProviders(<Todos />, { route: '/todos' });

    await waitFor(() => expect(requests.length).toBeGreaterThan(0));
    expect(requests.every((url) => url.searchParams.getAll('status').length === 0)).toBe(true);
    expect(localStorage.getItem('todos-status-filter')).toBe(JSON.stringify('ALL'));
  });
});
