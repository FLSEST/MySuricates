import { describe, it, expect } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server } from '../../test/server';
import { renderWithProviders } from '../../test/utils';
import { setupDashboardPageTests, slot } from '../../test/dashboardPage';
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
});
