import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { renderHook, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import type { DashboardData } from '../../../types';
import { DashboardDataProvider } from '../DashboardDataProvider';
import { useDashboardData } from '../dashboardDataContext';

let searches: string[] = [];

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => {
  server.resetHandlers();
  vi.restoreAllMocks();
});
afterAll(() => server.close());
beforeEach(() => {
  searches = [];
  server.use(
    http.get('/api/v1/learning/dashboard', ({ request }) => {
      searches.push(new URL(request.url).search);
      return HttpResponse.json({ totalWorkflows: 4 } as DashboardData);
    }),
  );
});

function Probe({ name }: { name: string }) {
  const { data, isLoading, days } = useDashboardData();
  return <p>{`${name}: ${isLoading ? 'loading' : String(data?.totalWorkflows)} (${days}d)`}</p>;
}

describe('DashboardDataProvider', () => {
  it('runs one query with the view filter and shares it with every widget', async () => {
    const { queryClient } = renderWithProviders(
      <DashboardDataProvider filter={{ days: 7, projectId: 'p1' }}>
        <Probe name="first" />
        <Probe name="second" />
      </DashboardDataProvider>,
    );

    expect(await screen.findByText('first: 4 (7d)')).toBeInTheDocument();
    expect(screen.getByText('second: 4 (7d)')).toBeInTheDocument();
    expect(searches).toEqual(['?days=7&projectId=p1']);
    const queries = queryClient.getQueryCache().findAll({ queryKey: ['learning', 'dashboard'] });
    expect(queries).toHaveLength(1);
    expect(queries[0]?.getObserversCount()).toBe(1);
  });

  it('loads the default 30 days for all projects without a filter', async () => {
    renderWithProviders(
      <DashboardDataProvider filter={undefined}>
        <Probe name="only" />
      </DashboardDataProvider>,
    );

    expect(await screen.findByText('only: 4 (30d)')).toBeInTheDocument();
    expect(searches).toEqual(['']);
  });

  it('fails loudly when a widget is rendered outside the provider', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => renderHook(() => useDashboardData())).toThrow('DashboardDataProvider');
  });
});
