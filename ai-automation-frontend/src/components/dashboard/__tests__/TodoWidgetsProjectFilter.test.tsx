import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { TodosWidget } from '../TodosWidget';
import { TodosDonutChart } from '../TodosDonutChart';
import { DashboardDataContext, type DashboardDataValue } from '../dashboardDataContext';
import type { DashboardViewFilter } from '../dashboardLayout';

let countSearches: string[] = [];
let listSearches: string[] = [];

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  countSearches = [];
  listSearches = [];
  useI18nStore.setState({ locale: 'en' });
  server.use(
    http.get('/api/v1/todos/count', ({ request }) => {
      const projectId = new URL(request.url).searchParams.get('projectId');
      countSearches.push(projectId ?? '');
      // p2 has fewer todos than the whole instance, so the total visibly changes.
      return HttpResponse.json(projectId === 'p2'
        ? { open: 1, inProgress: 0, done: 1, backlog: 0, archived: 0 }
        : { open: 3, inProgress: 1, done: 5, backlog: 0, archived: 0 });
    }),
    http.get('/api/v1/todos', ({ request }) => {
      listSearches.push(new URL(request.url).searchParams.get('projectId') ?? '');
      return HttpResponse.json({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 5 });
    }),
  );
});

function inView(ui: React.ReactElement, filter: DashboardViewFilter) {
  const value: DashboardDataValue = {
    data: undefined, isLoading: false, error: null, refetch: () => {},
    filter, days: 30, isPlaceholderData: false, dataDays: 30,
  };
  return <DashboardDataContext.Provider value={value}>{ui}</DashboardDataContext.Provider>;
}

describe('todo widgets follow the dashboard project filter', () => {
  it('TodosWidget queries counts and open todos for the selected project and refetches on a switch', async () => {
    const { rerender } = renderWithProviders(inView(<TodosWidget />, {}));
    await waitFor(() => expect(countSearches).toContain(''));
    expect(listSearches).toContain('');

    rerender(inView(<TodosWidget />, { projectId: 'p2' }));
    await waitFor(() => expect(countSearches).toContain('p2'));
    await waitFor(() => expect(listSearches).toContain('p2'));
    expect(await screen.findByText(/1 open/i)).toBeInTheDocument();
  });

  it('TodosDonutChart shows the selected project total after a switch', async () => {
    const { rerender } = renderWithProviders(inView(<TodosDonutChart />, {}));
    expect(await screen.findByText('9')).toBeInTheDocument();

    rerender(inView(<TodosDonutChart />, { projectId: 'p2' }));
    expect(await screen.findByText('2')).toBeInTheDocument();
    expect(countSearches).toEqual(['', 'p2']);
  });
});
