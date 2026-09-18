import { describe, it, expect } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { DASHBOARD_FIXTURE, setupDashboardPageTests, widgetOrder } from '../../test/dashboardPage';
import { useDashboardLayoutStore } from '../../stores/dashboardLayoutStore';
import { Dashboard } from '../Dashboard';

setupDashboardPageTests();

// Renders the whole page; see Dashboard.test.tsx for the timeout.
describe('Dashboard views', { timeout: 20_000 }, () => {
  it('dims the widgets while they still show the previous view’s data', async () => {
    let release = () => {};
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      http.get('/api/v1/learning/dashboard', async ({ request }) => {
        if (new URL(request.url).search === '?days=7') await gate;
        return HttpResponse.json(DASHBOARD_FIXTURE);
      }),
    );
    useDashboardLayoutStore.setState({
      activeViewId: 'default',
      views: [{ id: 'default', name: '', widgets: [] }, { id: 'week', name: 'Week', widgets: [], filter: { days: 7 } }],
    });
    renderWithProviders(<Dashboard />);
    expect(await screen.findByText('Recent Runs')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-widgets')).not.toHaveAttribute('aria-busy');

    fireEvent.click(screen.getByRole('tab', { name: 'Week' }));

    await waitFor(() => expect(screen.getByTestId('dashboard-widgets')).toHaveAttribute('aria-busy', 'true'));
    expect(screen.queryByTestId('widget-skeleton')).not.toBeInTheDocument();
    release();
    await waitFor(() => expect(screen.getByTestId('dashboard-widgets')).not.toHaveAttribute('aria-busy'));
  });

  it('marks the widget area as the panel of the shown view’s tab, but not in edit mode', async () => {
    useDashboardLayoutStore.setState({
      activeViewId: 'week',
      views: [{ id: 'default', name: '', widgets: [] }, { id: 'week', name: 'Week', widgets: [] }],
    });
    renderWithProviders(<Dashboard />);
    expect(await screen.findByText('Recent Runs')).toBeInTheDocument();

    const panel = screen.getByRole('tabpanel', { name: 'Week' });
    expect(panel).toHaveAttribute('id', 'dashboard-view-panel');
    expect(screen.getByRole('tab', { name: 'Week' })).toHaveAttribute('aria-controls', panel.id);

    fireEvent.click(screen.getByRole('button', { name: 'Edit dashboard' }));
    expect(screen.queryByRole('tabpanel')).not.toBeInTheDocument();
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
  });

  it('creates a view from the “Quality” template that shows only its widgets and saves it', async () => {
    let posted: { layout?: { views: { name: string; widgets: { instanceId: string; hidden?: boolean }[] }[] } } | undefined;
    server.use(
      http.post('/api/v1/dashboard/layouts', async ({ request }) => {
        posted = (await request.json()) as typeof posted;
        return HttpResponse.json({ id: 'v1', createdAt: '2026-09-15T10:00:00Z', deviceLabel: 'Laptop', formatVersion: 2, viewCount: 2, contentHash: 'h' }, { status: 201 });
      }),
    );
    renderWithProviders(<Dashboard />);
    expect(await screen.findByText('Recent Runs')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Edit dashboard' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add view from template' }));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Quality' }));

    expect(widgetOrder()).toEqual(['insights-gate-status', 'insights-failure-reasons', 'chart-success-rate']);
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));

    await waitFor(() => expect(posted?.layout?.views.map((v) => v.name)).toEqual(['', 'Quality']));
    const saved = posted?.layout?.views[1]?.widgets ?? [];
    expect(saved.filter((w) => !w.hidden).map((w) => w.instanceId)).toEqual(['insights-gate-status', 'insights-failure-reasons', 'chart-success-rate']);
    expect(saved.filter((w) => w.hidden)).toHaveLength(18);
  });
});
