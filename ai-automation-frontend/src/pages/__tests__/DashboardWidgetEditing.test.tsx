import { describe, it, expect } from 'vitest';
import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { DASHBOARD_FIXTURE, setupDashboardPageTests, slot } from '../../test/dashboardPage';
import { WIDGET_SIZE_CLASSES } from '../../components/dashboard/widgetRegistry';
import { Dashboard } from '../Dashboard';

setupDashboardPageTests();

type PostedLayout = { layout?: { views: { widgets: { instanceId: string; type: string; size?: string; hidden?: boolean }[] }[] } };

function capturePosts() {
  const posts: PostedLayout[] = [];
  server.use(
    http.post('/api/v1/dashboard/layouts', async ({ request }) => {
      posts.push((await request.json()) as PostedLayout);
      return HttpResponse.json({ id: 'v1', createdAt: '2026-09-15T10:00:00Z', deviceLabel: 'Laptop', formatVersion: 2, viewCount: 1, contentHash: 'h' }, { status: 201 });
    }),
  );
  return posts;
}

async function openEditMode() {
  renderWithProviders(<Dashboard />);
  expect(await screen.findByText('Recent Runs')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Edit dashboard' }));
}

// Renders the whole page; see Dashboard.test.tsx for the timeout.
describe('Dashboard widget editing', { timeout: 20_000 }, () => {
  it('resizes a widget from its size menu and saves the size with the layout', async () => {
    const posts = capturePosts();
    await openEditMode();
    expect(slot('recent-runs')?.className).toContain(WIDGET_SIZE_CLASSES.twoThirds);

    fireEvent.click(screen.getByRole('button', { name: 'Size: Recent Runs' }));
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Full width' }));

    expect(slot('recent-runs')?.className).not.toContain(WIDGET_SIZE_CLASSES.twoThirds);
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(posts[0]?.layout?.views[0]?.widgets).toContainEqual({ instanceId: 'recent-runs', type: 'recent-runs', size: 'full' }));
  });

  it('adds an Insights chart from the catalog, removes it again and saves both steps', async () => {
    server.use(
      http.get('/api/v1/learning/dashboard', () => HttpResponse.json({ ...DASHBOARD_FIXTURE, gateStatusSummary: [{ status: 'GREEN', count: 3 }] })),
    );
    const posts = capturePosts();
    await openEditMode();

    fireEvent.click(screen.getByRole('button', { name: 'Add widget' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('Quality gate results: green, yellow, red.')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Add: Quality Gates' }));
    expect(within(dialog).getByRole('button', { name: 'In view: Quality Gates' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));

    // Lazy: the chart's code loads on first use, the slot shows it afterwards.
    await waitFor(() => expect(slot('insights-gate-status')).toHaveTextContent('Quality Gates'), { timeout: 5000 });
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(posts[0]?.layout?.views[0]?.widgets).toContainEqual({ instanceId: 'insights-gate-status', type: 'insights-gate-status' }));

    // Editing is locked while the save runs.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit dashboard' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Edit dashboard' }));
    fireEvent.click(screen.getByRole('button', { name: 'Remove: Quality Gates' }));
    expect(slot('insights-gate-status')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(posts).toHaveLength(2));
    expect(posts[1]?.layout?.views[0]?.widgets.map((w) => w.instanceId)).not.toContain('insights-gate-status');
  });
});
