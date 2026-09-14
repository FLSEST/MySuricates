import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor, fireEvent } from '@testing-library/react';
import { http, HttpResponse, delay } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { modulesFixture } from '../../test/modules';
import { useI18nStore } from '../../i18n';
import { useDashboardLayoutStore } from '../../stores/dashboardLayoutStore';
import { createDefaultLayout } from '../../components/dashboard/dashboardLayout';
import { DASHBOARD_WIDGET_IDS } from '../../components/dashboard/widgetRegistry';
import type { DashboardData } from '../../types';
import { Dashboard } from '../Dashboard';

const DASHBOARD: DashboardData = {
  totalWorkflows: 4,
  totalRuns: 10,
  runsToday: 2,
  successfulRuns: 8,
  failedRuns: 2,
  averageDurationMs: 1000,
  successRate: 0.8,
  totalCostUsd30d: 1.5,
  totalTokens30d: 0,
  totalInputTokens: 0,
  totalOutputTokens: 0,
  topWorkflows: [],
  recentRuns: [],
  timeSeries: [],
  providerStats: [],
  integrationStats: [{ provider: 'trello', runCount: 3, totalTokens: 0, successRate: 1 }],
  modelStats: [],
};

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useDashboardLayoutStore.setState(createDefaultLayout());
  server.use(
    http.get('/api/v1/learning/dashboard', () => HttpResponse.json(DASHBOARD)),
    http.get('/api/v1/system/info', () => HttpResponse.json({ version: '1.0.0', buildType: 'SNAPSHOT' })),
    http.get('/api/v1/integrations', () => HttpResponse.json([])),
  );
});

function widgetOrder(): string[] {
  return Array.from(document.querySelectorAll('[data-widget-id]')).map((el) => el.getAttribute('data-widget-id') ?? '');
}

function slot(id: string): HTMLElement | null {
  return document.querySelector(`[data-widget-id="${id}"]`);
}

async function renderDashboard() {
  renderWithProviders(<Dashboard />);
  expect(await screen.findByText('Recent Runs')).toBeInTheDocument();
}

// Renders the whole page (19 widgets incl. charts) several times per test: ~1.5s alone,
// but past the 5s default when the full suite runs in parallel.
describe('Dashboard layout', { timeout: 20_000 }, () => {
  it('renders all registered widgets in registry order by default', async () => {
    await renderDashboard();

    expect(widgetOrder()).toEqual([...DASHBOARD_WIDGET_IDS]);
    // Widgets without data render nothing, their slot collapses via CSS (empty:hidden).
    expect(slot('provider-stats')).toBeEmptyDOMElement();
    expect(slot('model-stats')).toBeEmptyDOMElement();
    expect(await screen.findByText('Trello')).toBeInTheDocument();
  });

  it('hides a widget in edit mode and shows it again at its old position', async () => {
    await renderDashboard();
    fireEvent.click(screen.getByRole('button', { name: 'Edit dashboard' }));

    fireEvent.click(screen.getByRole('button', { name: 'Hide: Recent Runs' }));
    expect(slot('recent-runs')).toBeNull();
    expect(JSON.parse(localStorage.getItem('dashboard-layout') ?? '{}').state.views[0].widgets)
      .toContainEqual({ id: 'recent-runs', hidden: true });

    fireEvent.click(screen.getByRole('button', { name: 'Show: Recent Runs' }));
    expect(widgetOrder()).toEqual([...DASHBOARD_WIDGET_IDS]);
    expect(screen.queryByRole('button', { name: 'Show: Recent Runs' })).not.toBeInTheDocument();

    // Leaving edit mode keeps the layout and removes the edit controls.
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    expect(screen.queryByRole('button', { name: /^Hide:/ })).not.toBeInTheDocument();
    expect(widgetOrder()).toEqual([...DASHBOARD_WIDGET_IDS]);
  });

  it('keeps a stored custom order, skips unknown widgets and appends new ones', async () => {
    useDashboardLayoutStore.setState({
      views: [{
        id: 'default',
        name: '',
        widgets: [{ id: 'quick-actions' }, { id: 'widget-from-an-old-release' }, { id: 'stat-version', hidden: true }],
      }],
    });

    await renderDashboard();

    const expected = ['quick-actions', ...DASHBOARD_WIDGET_IDS.filter((id) => id !== 'quick-actions' && id !== 'stat-version')];
    expect(widgetOrder()).toEqual(expected);
  });

  it('resets a customized layout to the default', async () => {
    useDashboardLayoutStore.setState({
      views: [{ id: 'default', name: '', widgets: [{ id: 'todos' }, { id: 'stat-version', hidden: true }] }],
    });
    await renderDashboard();
    fireEvent.click(screen.getByRole('button', { name: 'Edit dashboard' }));

    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));

    expect(widgetOrder()).toEqual([...DASHBOARD_WIDGET_IDS]);
    expect(screen.queryByRole('button', { name: 'Reset' })).not.toBeInTheDocument();
  });

  it('leaves out widgets of modules the backend reports disabled, also in edit mode', async () => {
    server.use(
      http.get('/api/v1/system/capabilities', () => HttpResponse.json(modulesFixture('core', ['integrations']))),
    );
    await renderDashboard();

    await waitFor(() => expect(slot('integration-stats')).toBeNull(), { timeout: 5000 });
    fireEvent.click(screen.getByRole('button', { name: 'Edit dashboard' }));
    expect(slot('integration-stats')).toBeNull();
    expect(screen.queryByRole('button', { name: /Integrations/ })).not.toBeInTheDocument();
  });

  it('renders independent widgets while the dashboard data is still loading', async () => {
    server.use(
      http.get('/api/v1/learning/dashboard', async () => {
        await delay('infinite');
        return HttpResponse.json(DASHBOARD);
      }),
    );
    renderWithProviders(<Dashboard />);

    expect(await screen.findByText('Test Todo')).toBeInTheDocument();
    expect(screen.getAllByTestId('widget-skeleton').length).toBeGreaterThan(0);
    expect(screen.queryByText('Recent Runs')).not.toBeInTheDocument();
  });

  it('shows a retry banner when the dashboard data fails and keeps other widgets usable', async () => {
    server.use(
      http.get('/api/v1/learning/dashboard', () => HttpResponse.json({ message: 'boom' }, { status: 500 })),
    );
    renderWithProviders(<Dashboard />);

    expect(await screen.findByText(/Dashboard data unavailable/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(await screen.findByText('Test Todo')).toBeInTheDocument();
    // No fake zeros without data.
    expect(document.querySelector('[data-widget-id="stat-total-workflows"]')).toHaveTextContent('Total Workflows-');
  });
});
