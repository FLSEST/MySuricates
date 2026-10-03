import { failOnUnhandledRequest } from '../../test/msw';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { useLocation } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { useI18nStore } from '../../i18n';
import { en } from '../../i18n/en';
import { Chains } from '../Chains';
import { Schedules } from '../Schedules';

beforeAll(() => server.listen({ onUnhandledRequest: failOnUnhandledRequest }));
afterEach(() => {
  server.resetHandlers();
  localStorage.clear();
});
afterAll(() => server.close());
beforeEach(() => useI18nStore.setState({ locale: 'en' }));

function Search() {
  return <span data-testid="search">{useLocation().search}</span>;
}

const chain = (id: string, name: string) => ({
  id, name, description: null, icon: null, entries: [], stopOnFailure: false, runCount: 0, metadata: {},
  createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z',
});

const schedule = (id: string, workflowName: string, enabled: boolean) => ({
  id, workflowId: `w-${id}`, workflowName, projectName: null, chainId: null, chainName: null, targetType: 'WORKFLOW',
  cronExpression: '0 6 * * *', scheduleType: 'CRON', scheduledAt: null, delayExpression: null, description: '',
  inputContext: {}, enabled, nextRunAt: null, lastRunAt: null, createdAt: '2026-09-01T00:00:00Z', updatedAt: '2026-09-01T00:00:00Z',
});

// 3a35ced1: reports and runs link to one chain or schedule – there is no detail page, the list page selects it
describe('/chains?chain=<id>', () => {
  beforeEach(() => {
    server.use(http.get('/api/v1/chains', () => HttpResponse.json([chain('c1', 'First chain'), chain('c2', 'Linked chain')])));
  });

  it('selects the linked chain instead of the first, and keeps the selection in the URL', async () => {
    renderWithProviders(<><Chains /><Search /></>, { route: '/chains?chain=c2' });

    expect(await screen.findByRole('heading', { level: 2, name: /Linked chain/ })).toBeInTheDocument();
    expect(document.getElementById('chain-c2')).toHaveAttribute('aria-current', 'true');
    expect(document.getElementById('chain-c1')).not.toHaveAttribute('aria-current');

    fireEvent.click(screen.getByText('First chain'));
    await waitFor(() => expect(screen.getByTestId('search')).toHaveTextContent('?chain=c1'));
    expect(document.getElementById('chain-c1')).toHaveAttribute('aria-current', 'true');
  });

  it('falls back to the first chain when the linked one is gone', async () => {
    renderWithProviders(<Chains />, { route: '/chains?chain=gone' });

    expect(await screen.findByRole('heading', { level: 2, name: /First chain/ })).toBeInTheDocument();
  });
});

describe('/schedules?schedule=<id>', () => {
  beforeEach(() => {
    server.use(http.get('/api/v1/schedules', () => HttpResponse.json([
      schedule('s1', 'Paused report', false),
      schedule('s2', 'Linked report', true),
    ])));
  });

  it('highlights the linked schedule, shows it despite the filters and opens its run history', async () => {
    // A filter left on "off" from an earlier visit would hide the linked (enabled) schedule
    localStorage.setItem('schedules-enabled-filter', JSON.stringify('OFF'));
    renderWithProviders(<Schedules />, { route: '/schedules?schedule=s2' });

    expect(await screen.findByText('Linked report')).toBeInTheDocument();
    expect(screen.getByText('Paused report')).toBeInTheDocument();
    const row = document.getElementById('schedule-s2');
    expect(row).toHaveAttribute('aria-current', 'true');
    expect(row?.className).toContain('bg-accent-subtle');
    expect(document.getElementById('schedule-s1')).not.toHaveAttribute('aria-current');
    expect(await screen.findByText(en.schedules.noRuns)).toBeInTheDocument();
  });

  it('applies the filters as usual without a link', async () => {
    localStorage.setItem('schedules-enabled-filter', JSON.stringify('OFF'));
    renderWithProviders(<Schedules />, { route: '/schedules' });

    expect(await screen.findByText('Paused report')).toBeInTheDocument();
    expect(screen.queryByText('Linked report')).not.toBeInTheDocument();
    expect(screen.queryByText(en.schedules.noRuns)).not.toBeInTheDocument();
  });
});
