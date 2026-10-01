import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach, vi } from 'vitest';
import { renderHook, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import type { DashboardData } from '../../../types';
import { DashboardDataProvider } from '../DashboardDataProvider';
import { useDashboardData } from '../dashboardDataContext';
import { TotalCostStat } from '../StatWidgets';

let searches: string[] = [];

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => {
  server.resetHandlers();
  vi.restoreAllMocks();
});
afterAll(() => server.close());
beforeEach(() => {
  searches = [];
  useI18nStore.setState({ locale: 'en' });
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

  it('keeps the previous data with its own time range while another filter loads', async () => {
    let release = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      http.get('/api/v1/learning/dashboard', async ({ request }) => {
        const search = new URL(request.url).search;
        searches.push(search);
        if (search === '?days=7&projectId=p2') await gate;
        return HttpResponse.json({ totalWorkflows: search ? 7 : 30, totalCostUsd30d: 1 } as DashboardData);
      }),
    );
    function FilterProbe() {
      const { data, days, dataDays, isPlaceholderData, filter } = useDashboardData();
      return <p>{`${data?.totalWorkflows} for ${dataDays}d, asked ${days}d/${filter.projectId ?? 'all'}${isPlaceholderData ? ', placeholder' : ''}`}</p>;
    }
    const page = (filter: { days?: number; projectId?: string } | undefined) => (
      <DashboardDataProvider filter={filter}>
        <FilterProbe />
        <TotalCostStat />
      </DashboardDataProvider>
    );

    const { rerender } = renderWithProviders(page(undefined));
    expect(await screen.findByText('30 for 30d, asked 30d/all')).toBeInTheDocument();
    expect(screen.getByText('Cost (30d)')).toBeInTheDocument();

    rerender(page({ days: 7, projectId: 'p2' }));
    await waitFor(() => expect(searches).toContain('?days=7&projectId=p2'));
    // The old numbers stay on screen, labelled with their own range — no skeleton, no mislabel.
    expect(screen.getByText('30 for 30d, asked 7d/p2, placeholder')).toBeInTheDocument();
    expect(screen.getByText('Cost (30d)')).toBeInTheDocument();
    expect(screen.queryByTestId('widget-skeleton')).not.toBeInTheDocument();

    release();
    expect(await screen.findByText('7 for 7d, asked 7d/p2')).toBeInTheDocument();
    expect(screen.getByText('Cost (7d)')).toBeInTheDocument();
  });

  it('fails loudly when a widget is rendered outside the provider', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => renderHook(() => useDashboardData())).toThrow('DashboardDataProvider');
  });
});
