import { describe, it, expect, beforeEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, createMemoryRouter, RouterProvider, useLocation, useNavigationType } from 'react-router-dom';
import { useDashboardLayoutStore } from '../../stores/dashboardLayoutStore';
import { useDashboardSyncStore } from '../../stores/dashboardSyncStore';
import { useDashboardViewParam } from '../useDashboardViewParam';

function Harness() {
  useDashboardViewParam();
  const location = useLocation();
  const navigationType = useNavigationType();
  return <output data-testid="location" data-navigation={navigationType}>{location.search}</output>;
}

function renderAt(route: string): HTMLElement {
  render(
    <MemoryRouter initialEntries={[route]}>
      <Harness />
    </MemoryRouter>,
  );
  return screen.getByTestId('location');
}

/**
 * A data router (`createBrowserRouter`-style, like the real app) whose one route has a loader —
 * without it, a plain searchParams-only `navigate()` resolves synchronously inside `act()` here, so
 * the real app's async-transition timing (a synchronous zustand update can render before a queued
 * navigation commits) never reproduces under test. The loader forces every navigation through a
 * real (short) async gap, giving a test a window to land a store update mid-navigation.
 */
function renderAtWithDataRouter(route: string) {
  const router = createMemoryRouter(
    [{
      path: '/',
      Component: Harness,
      HydrateFallback: () => null,
      loader: () => new Promise((resolve) => setTimeout(resolve, 10)),
    }],
    { initialEntries: [route] },
  );
  render(<RouterProvider router={router} />);
  return router;
}

beforeEach(() => {
  localStorage.clear();
  useDashboardLayoutStore.setState({
    activeViewId: 'default',
    views: [{ id: 'default', name: '', widgets: [] }, { id: 'ops', name: 'Ops', widgets: [] }],
  });
  useDashboardSyncStore.setState({ baseVersionId: 'v1', dirty: false });
});

describe('useDashboardViewParam', () => {
  it('opens the view named in ?view= without marking a local change', async () => {
    const location = renderAt('/?view=ops');

    await waitFor(() => expect(useDashboardLayoutStore.getState().activeViewId).toBe('ops'));
    expect(location.textContent).toBe('?view=ops');
    expect(useDashboardSyncStore.getState().dirty).toBe(false);
  });

  it('keeps the current view for an unknown id and corrects the link', async () => {
    const location = renderAt('/?view=missing');

    await waitFor(() => expect(location.textContent).toBe('?view=default'));
    expect(useDashboardLayoutStore.getState().activeViewId).toBe('default');
  });

  it('leaves a plain link alone and writes the view once it changes, replacing the history entry', async () => {
    const location = renderAt('/?tab=1');
    expect(location.textContent).toBe('?tab=1');

    act(() => useDashboardLayoutStore.getState().setActiveView('ops'));

    await waitFor(() => expect(location.textContent).toBe('?tab=1&view=ops'));
    expect(location).toHaveAttribute('data-navigation', 'REPLACE');
    expect(useDashboardLayoutStore.getState().activeViewId).toBe('ops');
  });

  it('activates a view named in the link once it arrives via a later remote layout', async () => {
    useDashboardLayoutStore.setState({ activeViewId: 'default', views: [{ id: 'default', name: '', widgets: [] }] });
    const location = renderAt('/?view=ops');

    await waitFor(() => expect(location.textContent).toBe('?view=default'));
    expect(useDashboardLayoutStore.getState().activeViewId).toBe('default');

    act(() => {
      useDashboardLayoutStore.getState().applyRemoteLayout({
        activeViewId: 'default',
        views: [{ id: 'default', name: '', widgets: [] }, { id: 'ops', name: 'Ops', widgets: [] }],
      });
    });

    await waitFor(() => expect(useDashboardLayoutStore.getState().activeViewId).toBe('ops'));
    await waitFor(() => expect(location.textContent).toBe('?view=ops'));
    expect(useDashboardSyncStore.getState().dirty).toBe(false);
  });

  it('drops the pending view from the link once the user switches views before it arrives', async () => {
    useDashboardLayoutStore.setState({
      activeViewId: 'default',
      views: [{ id: 'default', name: '', widgets: [] }, { id: 'other', name: 'Other', widgets: [] }],
    });
    const location = renderAt('/?view=ops');
    await waitFor(() => expect(location.textContent).toBe('?view=default'));

    act(() => useDashboardLayoutStore.getState().setActiveView('other'));
    await waitFor(() => expect(location.textContent).toBe('?view=other'));

    act(() => {
      useDashboardLayoutStore.getState().applyRemoteLayout({
        activeViewId: 'other',
        views: [
          { id: 'default', name: '', widgets: [] },
          { id: 'other', name: 'Other', widgets: [] },
          { id: 'ops', name: 'Ops', widgets: [] },
        ],
      });
    });

    // The pending link was dropped — the user's own choice stays active.
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(useDashboardLayoutStore.getState().activeViewId).toBe('other');
  });

  it('still activates the pending view when the remote layout drops the remembered view (not a user switch)', async () => {
    useDashboardLayoutStore.setState({ activeViewId: 'default', views: [{ id: 'default', name: '', widgets: [] }] });
    const location = renderAt('/?view=ops');
    await waitFor(() => expect(location.textContent).toBe('?view=default'));

    act(() => {
      // The remote layout doesn't contain the remembered `default` at all — applyRemoteLayout falls
      // back to the incoming layout's first view (`x`), which is not a user switching away.
      useDashboardLayoutStore.getState().applyRemoteLayout({
        activeViewId: 'x',
        views: [{ id: 'x', name: 'X', widgets: [] }, { id: 'ops', name: 'Ops', widgets: [] }],
      });
    });

    await waitFor(() => expect(useDashboardLayoutStore.getState().activeViewId).toBe('ops'));
    await waitFor(() => expect(location.textContent).toBe('?view=ops'));
  });

  it('ends up at the right URL even when a data router navigation is still committing when the pending link resolves', async () => {
    useDashboardLayoutStore.setState({ activeViewId: 'default', views: [{ id: 'default', name: '', widgets: [] }] });
    const router = renderAtWithDataRouter('/?view=ops');
    await waitFor(() => expect(screen.queryByTestId('location')).not.toBeNull());

    // Land the remote layout while the hook's own URL correction (queued on mount, since the store
    // doesn't have `ops` yet) is still committing — reproducing the real-browser race (see R1).
    act(() => {
      useDashboardLayoutStore.getState().applyRemoteLayout({
        activeViewId: 'default',
        views: [{ id: 'default', name: '', widgets: [] }, { id: 'ops', name: 'Ops', widgets: [] }],
      });
    });

    await waitFor(() => expect(useDashboardLayoutStore.getState().activeViewId).toBe('ops'));
    await waitFor(() => expect(screen.getByTestId('location').textContent).toBe('?view=ops'));
    // `router.state.navigation.state` can read 'idle' for a tick between the stale correction
    // landing and a further one starting, so re-check after a settling delay instead of gating on
    // it — the URL must still (or again) read `ops`, not flip back once everything has landed.
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(screen.getByTestId('location').textContent).toBe('?view=ops');
    expect(router.state.location.search).toBe('?view=ops');
  });
});
