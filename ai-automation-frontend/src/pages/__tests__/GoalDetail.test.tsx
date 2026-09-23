import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor, within } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { GoalDetail } from '../GoalDetail';
import type { GoalDetail as GoalDetailData, GoalTodoItem } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

function item(overrides: Partial<GoalTodoItem>): GoalTodoItem {
  return {
    id: 'x', title: 'X', status: 'OPEN', priority: 'MEDIUM', projectId: 'p1', projectName: 'AutoPilot', tags: [],
    goalRank: null, complexity: null, suggestedEffort: null, ratingNote: null, ratedBy: null, ratedAt: null,
    openQuestion: null, updatedAt: '2026-09-23T00:00:00Z', ...overrides,
  };
}

const detail: GoalDetailData = {
  goal: {
    id: 'g1', title: 'Open-Source-Launch', description: 'Repo public, README with video', status: 'ACTIVE',
    targetDate: '2026-10-15T00:00:00Z', createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
    progress: { total: 4, open: 2, inProgress: 1, backlog: 0, done: 1, openQuestions: 1, percentDone: 25 },
  },
  todos: [
    item({ id: 'a', title: 'Privacy findings', goalRank: 1, complexity: 'M', suggestedEffort: 'medium', ratedBy: 'AI' }),
    item({ id: 'b', title: 'Style probe', goalRank: 2, status: 'IN_PROGRESS', openQuestion: 'Which look?' }),
    item({ id: 'c', title: 'Examples page', projectName: 'Site' }),
    item({ id: 'd', title: 'Banner', status: 'DONE' }),
  ],
  byProject: [
    { projectId: 'p1', projectName: 'AutoPilot', total: 3, done: 1 },
    { projectId: 'p2', projectName: 'Site', total: 1, done: 0 },
  ],
};

function renderGoal() {
  return renderWithProviders(
    <Routes><Route path="/goals/:goalId" element={<GoalDetail />} /></Routes>,
    { route: '/goals/g1' },
  );
}

describe('GoalDetail', () => {
  it('lists what comes next in work order, the open questions and the progress', async () => {
    server.use(http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)));
    renderGoal();

    await screen.findByRole('heading', { name: 'Open-Source-Launch' });
    const next = screen.getAllByTestId('goal-next-item').map((li) => within(li).getByRole('link').textContent);
    expect(next).toEqual(['Privacy findings', 'Style probe', 'Examples page']);
    expect(screen.getByTestId('goal-question')).toHaveTextContent('Which look?');
    expect(screen.getByTestId('goal-progress')).toHaveTextContent('1 of 4 done');
    expect(screen.getByText('Site: 0/1')).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'assessed by AI' })).toBeInTheDocument();
  });

  it('moving an item down sends the whole active order to the server', async () => {
    let sent: unknown = null;
    server.use(
      http.get('/api/v1/goals/g1', () => HttpResponse.json(detail)),
      http.put('/api/v1/goals/g1/order', async ({ request }) => {
        sent = await request.json();
        return HttpResponse.json(detail);
      }),
    );
    renderGoal();

    fireEvent.click(await screen.findByRole('button', { name: 'Move down: Privacy findings' }));

    await waitFor(() => expect(sent).toEqual({ todoIds: ['b', 'a', 'c'] }));
    expect(screen.getByRole('button', { name: 'Move up: Privacy findings' })).toBeDisabled();
  });
});
