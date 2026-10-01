import { failOnUnhandledRequest } from '../../../test/msw';
import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest';
import { act, screen } from '@testing-library/react';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { PlannedThisWeekStat, PlannedTodayStat, TotalCostStat } from '../StatWidgets';
import { DashboardDataContext, type DashboardDataValue } from '../dashboardDataContext';

/** The planned tiles read the view's project filter from the dashboard context. */
const NO_FILTER: DashboardDataValue = {
  data: undefined, isLoading: false, error: null, refetch: () => {},
  filter: {}, days: 30, isPlaceholderData: false, dataDays: 30,
};
const inDashboard = (ui: React.ReactElement) => (
  <DashboardDataContext.Provider value={NO_FILTER}>{ui}</DashboardDataContext.Provider>
);

// jsdom has no layout: whether a title fits is checked in the browser (cypress/e2e/dashboard.cy.ts).
// Here: the tile never cuts its title off by design and keeps the full name as tooltip.
describe('stat tiles', () => {
  beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
  afterEach(() => server.resetHandlers());
  afterAll(() => server.close());

  it('shows the full title, wraps instead of truncating and keeps it as tooltip', async () => {
    useI18nStore.setState({ locale: 'en' });
    renderWithProviders(inDashboard(<PlannedThisWeekStat />));

    const title = await screen.findByText('Planned This Week');
    expect(title).toHaveAttribute('title', 'Planned This Week');
    expect(title).not.toHaveClass('truncate');
  });

  // 6bb7ed36: costs came out of toFixed with a decimal point in the German UI as well
  it('writes the cost with the separators of the UI language', () => {
    const withCost = (totalCostUsd30d: number) => (
      <DashboardDataContext.Provider value={{ ...NO_FILTER, data: { totalCostUsd30d } as DashboardDataValue['data'] }}>
        <TotalCostStat />
      </DashboardDataContext.Provider>
    );
    useI18nStore.setState({ locale: 'de' });
    const { rerender } = renderWithProviders(withCost(12.5));
    expect(screen.getByText(/^12,50\s\$$/)).toBeInTheDocument();
    rerender(withCost(0.0042));
    expect(screen.getByText(/^0,0042\s\$$/)).toBeInTheDocument();

    act(() => useI18nStore.setState({ locale: 'en' }));
    rerender(withCost(0));
    expect(screen.getByText('$0.00')).toBeInTheDocument();
  });

  it('tells today and this week apart in German', async () => {
    useI18nStore.setState({ locale: 'de' });
    renderWithProviders(inDashboard(<><PlannedTodayStat /><PlannedThisWeekStat /></>));

    expect(await screen.findByText('Geplant heute')).toBeInTheDocument();
    expect(await screen.findByText('Geplant diese Woche')).toBeInTheDocument();
  });
});
