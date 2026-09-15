import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { screen, waitFor, fireEvent, within } from '@testing-library/react';
import { http, HttpResponse, delay } from 'msw';
import { useLocation } from 'react-router-dom';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { modulesFixture } from '../../test/modules';
import { useI18nStore } from '../../i18n';
import { useDashboardLayoutStore } from '../../stores/dashboardLayoutStore';
import { useDashboardSyncStore } from '../../stores/dashboardSyncStore';
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
  useDashboardSyncStore.setState({ deviceLabel: 'Laptop', baseVersionId: null, dirty: false });
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

function LocationProbe() {
  const location = useLocation();
  return <output data-testid="location">{location.search}</output>;
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
      .toContainEqual({ instanceId: 'recent-runs', type: 'recent-runs', hidden: true });

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
        widgets: [
          { instanceId: 'quick-actions', type: 'quick-actions' },
          { instanceId: 'widget-from-an-old-release', type: 'widget-from-an-old-release' },
          { instanceId: 'stat-version', type: 'stat-version', hidden: true },
        ],
      }],
    });

    await renderDashboard();

    const expected = ['quick-actions', ...DASHBOARD_WIDGET_IDS.filter((id) => id !== 'quick-actions' && id !== 'stat-version')];
    expect(widgetOrder()).toEqual(expected);
  });

  it('resets a customized layout to the default', async () => {
    useDashboardLayoutStore.setState({
      views: [{
        id: 'default',
        name: '',
        widgets: [{ instanceId: 'todos', type: 'todos' }, { instanceId: 'stat-version', type: 'stat-version', hidden: true }],
      }],
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

    expect(await screen.findByTestId('dashboard-data-error')).toHaveTextContent('Dashboard data unavailable');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
    expect(await screen.findByText('Test Todo')).toBeInTheDocument();
    // No fake zeros without data.
    expect(document.querySelector('[data-widget-id="stat-total-workflows"]')).toHaveTextContent('Total Workflows-');
  });

  it('renders a customized layout stored in the v1 format after migration', async () => {
    localStorage.setItem('dashboard-layout', JSON.stringify({
      version: 1,
      state: {
        activeViewId: 'default',
        views: [{ id: 'default', name: '', widgets: [{ id: 'quick-actions' }, { id: 'stat-version', hidden: true }] }],
      },
    }));
    await useDashboardLayoutStore.persist.rehydrate();

    await renderDashboard();

    const expected = ['quick-actions', ...DASHBOARD_WIDGET_IDS.filter((id) => id !== 'quick-actions' && id !== 'stat-version')];
    expect(widgetOrder()).toEqual(expected);
  });

  it('saves a snapshot when finishing an edit that changed something', async () => {
    let posted: { layout?: { views: { widgets: unknown[] }[] } } | undefined;
    server.use(
      http.post('/api/v1/dashboard/layouts', async ({ request }) => {
        posted = (await request.json()) as typeof posted;
        return HttpResponse.json({ id: 'v1', createdAt: '2026-09-15T10:00:00Z', deviceLabel: 'Laptop', formatVersion: 2, viewCount: 1, contentHash: 'h' }, { status: 201 });
      }),
    );
    await renderDashboard();

    fireEvent.click(screen.getByRole('button', { name: 'Edit dashboard' }));
    fireEvent.click(screen.getByRole('button', { name: 'Hide: Recent Runs' }));
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));

    await waitFor(() => expect(posted?.layout?.views[0]?.widgets).toContainEqual({ instanceId: 'recent-runs', type: 'recent-runs', hidden: true }));
    await waitFor(() => expect(useDashboardSyncStore.getState()).toMatchObject({ baseVersionId: 'v1', dirty: false }));
  });

  it('does not save when an edit changed nothing', async () => {
    let posts = 0;
    server.use(http.post('/api/v1/dashboard/layouts', () => {
      posts += 1;
      return HttpResponse.json({}, { status: 201 });
    }));
    await renderDashboard();

    fireEvent.click(screen.getByRole('button', { name: 'Edit dashboard' }));
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(posts).toBe(0);
  });

  it('asks before replacing local changes with a newer server layout', async () => {
    useDashboardSyncStore.setState({ baseVersionId: 'v1', dirty: true });
    // Genuinely different from the remote's empty default view below — otherwise the mount check's
    // content comparison (spec item 7) would find them equal and sync silently instead of asking.
    useDashboardLayoutStore.setState({ views: [{ id: 'default', name: '', widgets: [{ instanceId: 'todos', type: 'todos' }] }] });
    let posted: { force?: boolean } | undefined;
    server.use(
      http.get('/api/v1/dashboard/layouts', () => HttpResponse.json({
        content: [{ id: 'v2', createdAt: '2026-09-15T10:00:00Z', deviceLabel: 'Desktop', formatVersion: 2, viewCount: 1, contentHash: 'h2' }],
        page: 0, size: 1, totalElements: 1, totalPages: 1,
      })),
      // The sync hook's mount check re-reads the version's content before deciding, so the
      // detail GET (not only the list) must be mocked too — see useDashboardLayoutSync.test.tsx.
      http.get('/api/v1/dashboard/layouts/:id', () => HttpResponse.json({
        id: 'v2', createdAt: '2026-09-15T10:00:00Z', deviceLabel: 'Desktop', formatVersion: 2, viewCount: 1, contentHash: 'h2',
        layout: { activeViewId: 'default', views: [{ id: 'default', name: '', widgets: [] }] },
      })),
      http.post('/api/v1/dashboard/layouts', async ({ request }) => {
        posted = (await request.json()) as typeof posted;
        return HttpResponse.json({ id: 'v3', createdAt: '2026-09-15T10:01:00Z', deviceLabel: 'Laptop', formatVersion: 2, viewCount: 1, contentHash: 'h3' }, { status: 201 });
      }),
    );
    await renderDashboard();

    const banner = await screen.findByTestId('layout-sync-conflict');
    expect(banner).toHaveTextContent('Desktop');
    fireEvent.click(within(banner).getByRole('button', { name: 'Keep mine as newest version' }));

    await waitFor(() => expect(posted?.force).toBe(true));
    await waitFor(() => expect(screen.queryByTestId('layout-sync-conflict')).not.toBeInTheDocument());
  });

  it('renders a device label containing replacement patterns literally, not as an interpreted pattern', async () => {
    useDashboardSyncStore.setState({ baseVersionId: 'v1', dirty: true });
    // Genuinely different from the remote's empty default view below, so the mount check finds a
    // real conflict (spec item 7) instead of syncing silently.
    useDashboardLayoutStore.setState({ views: [{ id: 'default', name: '', widgets: [{ instanceId: 'todos', type: 'todos' }] }] });
    const maliciousLabel = '$&{time}';
    server.use(
      http.get('/api/v1/dashboard/layouts', () => HttpResponse.json({
        content: [{ id: 'v2', createdAt: '2026-09-15T10:00:00Z', deviceLabel: maliciousLabel, formatVersion: 2, viewCount: 1, contentHash: 'h2' }],
        page: 0, size: 1, totalElements: 1, totalPages: 1,
      })),
      http.get('/api/v1/dashboard/layouts/:id', () => HttpResponse.json({
        id: 'v2', createdAt: '2026-09-15T10:00:00Z', deviceLabel: maliciousLabel, formatVersion: 2, viewCount: 1, contentHash: 'h2',
        layout: { activeViewId: 'default', views: [{ id: 'default', name: '', widgets: [] }] },
      })),
    );
    await renderDashboard();

    const banner = await screen.findByTestId('layout-sync-conflict');
    // A plain-string replacement would expand "$&" into the matched placeholder text
    // ("{device}") instead of the literal label — that must not happen.
    expect(banner).toHaveTextContent('$&{time}');
    expect(banner).not.toHaveTextContent('{device}');
  });

  it('loads the dashboard data once for the active view and its filter', async () => {
    const searches: string[] = [];
    server.use(
      http.get('/api/v1/learning/dashboard', ({ request }) => {
        searches.push(new URL(request.url).search);
        return HttpResponse.json(DASHBOARD);
      }),
    );
    useDashboardLayoutStore.setState({ views: [{ id: 'default', name: '', widgets: [], filter: { days: 7, projectId: 'p1' } }] });

    const { queryClient } = renderWithProviders(<Dashboard />);
    expect(await screen.findByText('Recent Runs')).toBeInTheDocument();

    expect(searches.length).toBeGreaterThan(0);
    expect(searches.every((search) => search === '?days=7&projectId=p1')).toBe(true);
    const queries = queryClient.getQueryCache().findAll({ queryKey: ['learning', 'dashboard'] });
    expect(queries).toHaveLength(1);
    expect(queries[0]?.getObserversCount()).toBe(1);
    expect(slot('stat-total-cost')).toHaveTextContent('Cost (7d)');
  });

  it('opens the view named in the link and switches views without saving', async () => {
    let posts = 0;
    server.use(http.post('/api/v1/dashboard/layouts', () => {
      posts += 1;
      return HttpResponse.json({}, { status: 201 });
    }));
    useDashboardLayoutStore.setState({
      activeViewId: 'default',
      views: [
        { id: 'default', name: '', widgets: [] },
        { id: 'ops', name: 'Ops', widgets: [{ instanceId: 'recent-runs', type: 'recent-runs', hidden: true }] },
      ],
    });
    renderWithProviders(<><Dashboard /><LocationProbe /></>, { route: '/?view=ops' });

    await waitFor(() => expect(screen.getByRole('tab', { name: 'Ops' })).toHaveAttribute('aria-selected', 'true'));
    expect(await screen.findByText('Test Todo')).toBeInTheDocument();
    expect(slot('recent-runs')).toBeNull();

    fireEvent.click(screen.getByRole('tab', { name: 'Overview' }));

    expect(await screen.findByText('Recent Runs')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('?view=default'));
    expect(useDashboardSyncStore.getState().dirty).toBe(false);
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(posts).toBe(0);
  });

  it('adds and names a view in edit mode and saves all views when done', async () => {
    let posted: { layout?: { activeViewId: string; views: { id: string; name: string }[] } } | undefined;
    server.use(
      http.post('/api/v1/dashboard/layouts', async ({ request }) => {
        posted = (await request.json()) as typeof posted;
        return HttpResponse.json({ id: 'v1', createdAt: '2026-09-15T10:00:00Z', deviceLabel: 'Laptop', formatVersion: 2, viewCount: 2, contentHash: 'h' }, { status: 201 });
      }),
    );
    renderWithProviders(<><Dashboard /><LocationProbe /></>);
    expect(await screen.findByText('Recent Runs')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Edit dashboard' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add view' }));
    fireEvent.click(screen.getByRole('button', { name: 'Rename view' }));
    const nameField = screen.getByRole('textbox', { name: 'View name' });
    fireEvent.change(nameField, { target: { value: 'Costs' } });
    fireEvent.keyDown(nameField, { key: 'Enter' });
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));

    await waitFor(() => expect(posted?.layout?.views.map((v) => v.name)).toEqual(['', 'Costs']));
    const newViewId = posted?.layout?.views[1]?.id ?? '';
    expect(newViewId).toMatch(/^view-[0-9a-f]{8}$/);
    expect(posted?.layout?.activeViewId).toBe(newViewId);
    expect(screen.getByTestId('location')).toHaveTextContent(`?view=${newViewId}`);
  });

  it('filters the active view from edit mode and reloads the dashboard data for it', async () => {
    const searches: string[] = [];
    server.use(
      http.get('/api/v1/learning/dashboard', ({ request }) => {
        searches.push(new URL(request.url).search);
        return HttpResponse.json(DASHBOARD);
      }),
    );
    await renderDashboard();

    fireEvent.click(screen.getByRole('button', { name: 'Edit dashboard' }));
    expect(await screen.findByRole('option', { name: 'Test Project' })).toBeInTheDocument();
    fireEvent.change(screen.getByRole('combobox', { name: 'Project' }), { target: { value: 'p1' } });
    fireEvent.click(screen.getByRole('button', { name: '7d' }));

    await waitFor(() => expect(searches).toContain('?days=7&projectId=p1'));
    expect(useDashboardLayoutStore.getState().views[0]?.filter).toEqual({ projectId: 'p1', days: 7 });
  });

  it('locks editing while a server version is being applied', async () => {
    useDashboardSyncStore.setState({ baseVersionId: 'v1', dirty: true });
    useDashboardLayoutStore.setState({ views: [{ id: 'default', name: '', widgets: [{ instanceId: 'todos', type: 'todos' }] }] });
    const remote = { id: 'v2', createdAt: '2026-09-15T10:00:00Z', deviceLabel: 'Desktop', formatVersion: 2, viewCount: 1, contentHash: 'h2' };
    let releaseDetail = () => {};
    const detailGate = new Promise<void>((resolve) => {
      releaseDetail = resolve;
    });
    let detailCalls = 0;
    server.use(
      http.get('/api/v1/dashboard/layouts', () => HttpResponse.json({ content: [remote], page: 0, size: 1, totalElements: 1, totalPages: 1 })),
      http.get('/api/v1/dashboard/layouts/:id', async () => {
        detailCalls += 1;
        // The mount check gets its answer at once; "Use that version" waits until the test releases it.
        if (detailCalls > 1) await detailGate;
        return HttpResponse.json({ ...remote, layout: { activeViewId: 'default', views: [{ id: 'default', name: '', widgets: [] }] } });
      }),
    );
    await renderDashboard();
    const banner = await screen.findByTestId('layout-sync-conflict');

    fireEvent.click(within(banner).getByRole('button', { name: 'Use that version' }));

    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit dashboard' })).toBeDisabled());
    releaseDetail();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit dashboard' })).toBeEnabled());
    expect(screen.queryByTestId('layout-sync-conflict')).not.toBeInTheDocument();
  });
});
