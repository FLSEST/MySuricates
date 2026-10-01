import { failOnUnhandledRequest } from '../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { Goals } from '../Goals';
import type { Goal } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

function goal(overrides: Partial<Goal>): Goal {
  return {
    id: 'g', title: 'Goal', description: null, status: 'ACTIVE', targetDate: null,
    createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
    progress: { total: 2, open: 1, inProgress: 0, backlog: 0, done: 1, openQuestions: 0, percentDone: 50 },
    ...overrides,
  };
}

/** Answers `GET /goals?status=…` with the goals of that status, and records what was asked for. */
function serveGoals(goals: Goal[], asked: string[] = []) {
  server.use(http.get('/api/v1/goals', ({ request }) => {
    const statuses = new URL(request.url).searchParams.getAll('status');
    asked.push(statuses.join(','));
    return HttpResponse.json(goals.filter((g) => statuses.includes(g.status)));
  }));
}

describe('Goals page', () => {
  it('shows the active goals first and switches to reached and archived ones per tab', async () => {
    const asked: string[] = [];
    serveGoals([
      goal({ id: 'a', title: 'Open-Source-Launch' }),
      goal({ id: 'd', title: 'Tray icon', status: 'DONE' }),
      goal({ id: 'x', title: 'Old plan', status: 'ARCHIVED' }),
    ], asked);
    renderWithProviders(<Goals />);

    expect(await screen.findByRole('heading', { level: 1, name: 'Goals' })).toBeInTheDocument();
    expect(await screen.findByRole('button', { name: 'Open-Source-Launch' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Tray icon' })).not.toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Active' })).toHaveAttribute('aria-selected', 'true');

    fireEvent.click(screen.getByRole('tab', { name: 'Archived' }));
    expect(await screen.findByRole('button', { name: 'Old plan' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Open-Source-Launch' })).not.toBeInTheDocument();
    expect(asked).toContain('ARCHIVED');

    fireEvent.click(screen.getByRole('tab', { name: 'Done' }));
    expect(await screen.findByRole('button', { name: 'Tray icon' })).toBeInTheDocument();
  });

  it('explains goals while there is none and creates one from the empty state', async () => {
    let body: unknown = null;
    serveGoals([]);
    server.use(http.post('/api/v1/goals', async ({ request }) => {
      body = await request.json();
      return HttpResponse.json(goal({ id: 'new', title: 'Launch' }), { status: 201 });
    }));
    renderWithProviders(<Goals />);

    expect(await screen.findByText('No active goal')).toBeInTheDocument();
    expect(screen.getByText(/shows how far along you are/)).toBeInTheDocument();
    fireEvent.click(screen.getAllByRole('button', { name: /New goal/ })[0]!);
    fireEvent.change(screen.getByLabelText('Title'), { target: { value: 'Launch' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => expect(body).toEqual({ title: 'Launch' }));
  });

  it('shows an error with a retry when the goals cannot be loaded', async () => {
    server.use(http.get('/api/v1/goals', () => HttpResponse.json({ message: 'boom' }, { status: 500 })));
    renderWithProviders(<Goals />);

    expect(await screen.findByRole('button', { name: /retry/i }, { timeout: 5000 })).toBeInTheDocument();
  });
});
