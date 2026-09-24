import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, fireEvent, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { GoalsSection } from '../GoalsSection';
import type { Goal } from '../../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

const goal: Goal = {
  id: 'g1', title: 'Open-Source-Launch', description: null, status: 'ACTIVE', targetDate: null,
  createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z',
  progress: { total: 5, open: 2, inProgress: 1, backlog: 0, done: 2, openQuestions: 3, percentDone: 40 },
};

describe('GoalsSection', () => {
  it('shows each goal with its progress and open questions', async () => {
    server.use(http.get('/api/v1/goals', () => HttpResponse.json([goal])));
    renderWithProviders(<GoalsSection />);

    expect(await screen.findByRole('button', { name: 'Open-Source-Launch' })).toBeInTheDocument();
    expect(screen.getByText('2 of 5 done')).toBeInTheDocument();
    expect(screen.getByText('Open questions: 3')).toBeInTheDocument();
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
});
