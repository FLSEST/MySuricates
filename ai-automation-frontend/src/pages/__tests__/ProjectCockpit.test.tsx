import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { ProjectCockpit } from '../ProjectCockpit';
import type { MilestoneAttentionItem, NeedsYouItem, TodoOverviewResponse } from '../../types';

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
});

const overdueTodo: NeedsYouItem = {
  id: 't1', title: 'Remove private data', projectId: 'p1', projectName: 'AutoPilot', priority: 'HIGH',
  dueDate: '2026-09-20T21:59:59Z', source: 'USER', createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z',
  ageDays: 23, overdue: true,
};

function milestoneDue(overrides: Partial<MilestoneAttentionItem>): MilestoneAttentionItem {
  return {
    id: 'm1', title: 'Beta', goalId: 'g1', goalTitle: 'Open-Source-Launch', dueDate: new Date(2026, 8, 22, 23, 59, 59).toISOString(),
    state: 'OVERDUE', total: 3, done: 1, ...overrides,
  };
}

function serveOverview(overview: Partial<TodoOverviewResponse>) {
  server.use(
    http.get('/api/v1/todos/overview', () => HttpResponse.json({
      projects: [], needsYou: [], generatedAt: '2026-09-24T08:00:00Z', staleThresholdDays: 30, milestonesDue: [], ...overview,
    })),
    http.get('/api/v1/goals', () => HttpResponse.json([])),
  );
}

describe('ProjectCockpit – "Needs you"', () => {
  // c60bf914: milestones (diamond) and work packages past their end (bar) – an older backend sends no kind: packages
  it('lists milestones and work packages that are overdue or due soon, each linking to its goal', async () => {
    serveOverview({
      needsYou: [overdueTodo],
      milestonesDue: [
        milestoneDue({ kind: 'WORK_PACKAGE' }),
        milestoneDue({ id: 'm2', kind: 'MILESTONE', title: 'RC', goalId: 'g2', goalTitle: 'Website', state: 'DUE_SOON', done: 0, total: 2 }),
        milestoneDue({ id: 'm3', title: 'Old stage', state: 'OVERDUE' }),
      ],
    });
    renderWithProviders(<ProjectCockpit />);

    const beta = await screen.findByRole('link', { name: /Beta/ });
    expect(beta).toHaveAttribute('href', '/goals/g1');
    expect(beta).toHaveAttribute('data-kind', 'work-package');
    expect(beta).toHaveTextContent('Open-Source-Launch');
    expect(beta).toHaveTextContent('overdue');
    expect(beta).toHaveTextContent('1/3');
    expect(within(beta).getByTitle('1 of 3 done')).toBeInTheDocument();
    const rc = screen.getByRole('link', { name: /RC/ });
    expect(rc).toHaveAttribute('href', '/goals/g2');
    expect(rc).toHaveAttribute('data-kind', 'milestone');
    expect(rc).toHaveTextContent('due soon');
    // a milestone counts its linked packages
    expect(within(rc).getByTitle('0 of 2 linked packages complete')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Old stage/ })).toHaveAttribute('data-kind', 'work-package');

    const lane = screen.getByRole('region', { name: /Needs you/ });
    expect(within(lane).getByText('Remove private data')).toBeInTheDocument();
    expect(within(lane).getByText('4')).toBeInTheDocument();
  });

  it('counts as nothing urgent only when neither TODOs nor milestones need attention', async () => {
    serveOverview({ milestonesDue: [milestoneDue({})] });
    renderWithProviders(<ProjectCockpit />);

    expect(await screen.findByRole('link', { name: /Beta/ })).toBeInTheDocument();
    expect(screen.queryByText('Nothing urgent — all under control.')).not.toBeInTheDocument();
  });

  it('copes with an older backend without milestonesDue', async () => {
    server.use(
      http.get('/api/v1/todos/overview', () => HttpResponse.json({
        projects: [], needsYou: [], generatedAt: '2026-09-24T08:00:00Z', staleThresholdDays: 30,
      })),
      http.get('/api/v1/goals', () => HttpResponse.json([])),
    );
    renderWithProviders(<ProjectCockpit />);

    expect(await screen.findByText('Nothing urgent — all under control.')).toBeInTheDocument();
  });
});
