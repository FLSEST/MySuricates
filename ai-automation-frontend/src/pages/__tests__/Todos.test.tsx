import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, vi } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { Todos } from '../Todos';
import { SEARCH_DEBOUNCE_MS } from '../../components/todo/todoConstants';
import type { Todo } from '../../types';

const BASE = '/api/v1';

const deepTodo: Todo = {
  id: 't-deep',
  title: 'Deep linked todo',
  description: null,
  status: 'OPEN',
  priority: 'MEDIUM',
  tags: ['frontend'],
  projectId: 'p1',
  projectName: 'Test Project',
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
} as Todo;

/** Registers the single-todo endpoint. `/todos/count` must stay ahead of `/todos/:id`. */
function useTodoEndpoints(todos: Todo[]) {
  const byId = vi.fn();
  server.use(
    http.get(`${BASE}/todos/count`, () => HttpResponse.json({ open: 1, inProgress: 0, done: 0, backlog: 0, archived: 0 })),
    http.get(`${BASE}/todos/:id`, ({ params }) => {
      byId(params.id);
      const found = todos.find((todo) => todo.id === params.id);
      return found ? HttpResponse.json(found) : new HttpResponse(null, { status: 404 });
    }),
    http.get(`${BASE}/todos`, () =>
      HttpResponse.json({ content: todos, page: 0, size: 30, totalElements: todos.length, totalPages: 1 }),
    ),
    http.get(`${BASE}/integrations`, () => HttpResponse.json([])),
    http.get(`${BASE}/todo-triggers`, () => HttpResponse.json([])),
  );
  return byId;
}

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());

describe('Todos — deep link ?todoId=', () => {
  it('opens the edit modal for the linked todo', async () => {
    useTodoEndpoints([deepTodo]);
    renderWithProviders(<Todos />, { route: '/todos?todoId=t-deep&projectId=p1' });

    // The modal renders the title in an input
    expect(await screen.findByDisplayValue('Deep linked todo')).toBeInTheDocument();
  });

  it('preselects the project of the linked todo when the link carries no projectId', async () => {
    useTodoEndpoints([deepTodo]);
    renderWithProviders(<Todos />, { route: '/todos?todoId=t-deep' });

    await waitFor(() => {
      expect(localStorage.getItem('todos-project-filter')).toBe(JSON.stringify('p1'));
    });
  });

  it('releases persisted filters that would hide the linked todo', async () => {
    localStorage.setItem('todos-status-filter', JSON.stringify('DONE'));
    localStorage.setItem('todos-tag-filter', JSON.stringify('backend'));
    useTodoEndpoints([deepTodo]);
    renderWithProviders(<Todos />, { route: '/todos?todoId=t-deep' });

    await waitFor(() => {
      expect(localStorage.getItem('todos-status-filter')).toBe(JSON.stringify('ALL'));
    });
    // 'backend' is not one of the todo's tags → filter cleared
    expect(localStorage.getItem('todos-tag-filter')).toBe(JSON.stringify(''));
  });

  it('keeps a persisted filter that already matches the linked todo', async () => {
    localStorage.setItem('todos-status-filter', JSON.stringify('OPEN'));
    localStorage.setItem('todos-tag-filter', JSON.stringify('frontend'));
    useTodoEndpoints([deepTodo]);
    renderWithProviders(<Todos />, { route: '/todos?todoId=t-deep' });

    expect(await screen.findByDisplayValue('Deep linked todo')).toBeInTheDocument();
    expect(localStorage.getItem('todos-status-filter')).toBe(JSON.stringify('OPEN'));
    expect(localStorage.getItem('todos-tag-filter')).toBe(JSON.stringify('frontend'));
  });

  it('marks the linked row as addressable and highlighted', async () => {
    useTodoEndpoints([deepTodo]);
    const { container } = renderWithProviders(<Todos />, { route: '/todos?todoId=t-deep' });

    await waitFor(() => {
      const row = container.querySelector('#todo-t-deep');
      expect(row).not.toBeNull();
      expect(row?.className).toContain('ring-accent/40');
    });
  });

  it('without the param it neither fetches a single todo nor opens the modal', async () => {
    const byId = useTodoEndpoints([deepTodo]);
    renderWithProviders(<Todos />, { route: '/todos' });

    await screen.findByText('Deep linked todo');
    expect(byId).not.toHaveBeenCalled();
    expect(screen.queryByDisplayValue('Deep linked todo')).toBeNull();
  });

  it('renders the list normally when the linked todo no longer exists', async () => {
    useTodoEndpoints([deepTodo]);
    renderWithProviders(<Todos />, { route: '/todos?todoId=gone' });

    expect(await screen.findByText('Deep linked todo')).toBeInTheDocument();
    expect(screen.queryByDisplayValue('Deep linked todo')).toBeNull();
  });
});

describe('Todos — search debounce', () => {
  /** Registers a /todos endpoint that records every free-text query it is asked for. */
  function recordingEndpoints(queries: string[]) {
    server.use(
      http.get(`${BASE}/todos/count`, () => HttpResponse.json({ open: 0, inProgress: 0, done: 0, backlog: 0, archived: 0 })),
      http.get(`${BASE}/todos`, ({ request }) => {
        const q = new URL(request.url).searchParams.get('q');
        if (q) queries.push(q);
        return HttpResponse.json({ content: [], page: 0, size: 30, totalElements: 0, totalPages: 0 });
      }),
      http.get(`${BASE}/integrations`, () => HttpResponse.json([])),
      http.get(`${BASE}/todo-triggers`, () => HttpResponse.json([])),
    );
  }

  it('sends one query for a burst of keystrokes, not one per character', async () => {
    const queries: string[] = [];
    recordingEndpoints(queries);
    renderWithProviders(<Todos />, { route: '/todos' });

    const input = await screen.findByPlaceholderText('Search...');
    fireEvent.change(input, { target: { value: 'l' } });
    fireEvent.change(input, { target: { value: 'lo' } });
    fireEvent.change(input, { target: { value: 'log' } });
    expect(queries).toEqual([]);   // nothing goes out while the user is still typing

    await waitFor(() => expect(queries).toEqual(['log']), { timeout: SEARCH_DEBOUNCE_MS * 10 });
  });
});

describe('Todos — quick add', () => {
  function recordCreates(created: unknown[]) {
    server.use(
      http.post(`${BASE}/todos`, async ({ request }) => {
        created.push(await request.json());
        return HttpResponse.json({ ...deepTodo, id: 't-new' });
      }),
    );
  }

  it("'n' outside of form fields focuses the title, inside a field it types normally", async () => {
    useTodoEndpoints([]);
    renderWithProviders(<Todos />, { route: '/todos' });
    const title = await screen.findByPlaceholderText('New Todo... (n)');
    const search = screen.getByPlaceholderText('Search...');

    search.focus();
    expect(fireEvent.keyDown(search, { key: 'n' })).toBe(true);   // not prevented
    expect(document.activeElement).toBe(search);

    (document.activeElement as HTMLElement).blur();
    expect(fireEvent.keyDown(document.body, { key: 'n' })).toBe(false);   // prevented
    expect(document.activeElement).toBe(title);

    title.blur();
    fireEvent.keyDown(document.body, { key: 'n', ctrlKey: true });
    expect(document.activeElement).not.toBe(title);
  });

  it('creates the todo on Enter, then clears the bar and focuses the title again', async () => {
    const created: unknown[] = [];
    useTodoEndpoints([]);
    recordCreates(created);
    renderWithProviders(<Todos />, { route: '/todos' });
    const title = await screen.findByPlaceholderText('New Todo... (n)');
    const description = screen.getByPlaceholderText('Description...');

    fireEvent.change(title, { target: { value: '  Write docs  ' } });
    fireEvent.change(description, { target: { value: ' Explain the hook ' } });
    fireEvent.keyDown(description, { key: 'Enter' });

    await waitFor(() => expect(created).toEqual([
      { title: 'Write docs', description: 'Explain the hook', priority: 'MEDIUM' },
    ]));
    await waitFor(() => expect(title).toHaveValue(''));
    expect(description).toHaveValue('');
    expect(document.activeElement).toBe(title);
  });

  it('ignores Enter without a title', async () => {
    const created: unknown[] = [];
    useTodoEndpoints([]);
    recordCreates(created);
    renderWithProviders(<Todos />, { route: '/todos' });
    const title = await screen.findByPlaceholderText('New Todo... (n)');

    fireEvent.change(title, { target: { value: '   ' } });
    fireEvent.keyDown(title, { key: 'Enter' });
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
    await new Promise((r) => setTimeout(r, 50));
    expect(created).toEqual([]);
  });
});
