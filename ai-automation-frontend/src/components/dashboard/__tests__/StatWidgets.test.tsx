import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest';
import { screen } from '@testing-library/react';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { PlannedThisWeekStat, PlannedTodayStat } from '../StatWidgets';

// jsdom has no layout: whether a title fits is checked in the browser (cypress/e2e/dashboard.cy.ts).
// Here: the tile never cuts its title off by design and keeps the full name as tooltip.
describe('stat tiles', () => {
  beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
  afterEach(() => server.resetHandlers());
  afterAll(() => server.close());

  it('shows the full title, wraps instead of truncating and keeps it as tooltip', async () => {
    useI18nStore.setState({ locale: 'en' });
    renderWithProviders(<PlannedThisWeekStat />);

    const title = await screen.findByText('Planned This Week');
    expect(title).toHaveAttribute('title', 'Planned This Week');
    expect(title).not.toHaveClass('truncate');
  });

  it('tells today and this week apart in German', async () => {
    useI18nStore.setState({ locale: 'de' });
    renderWithProviders(<><PlannedTodayStat /><PlannedThisWeekStat /></>);

    expect(await screen.findByText('Geplant heute')).toBeInTheDocument();
    expect(await screen.findByText('Geplant diese Woche')).toBeInTheDocument();
  });
});
