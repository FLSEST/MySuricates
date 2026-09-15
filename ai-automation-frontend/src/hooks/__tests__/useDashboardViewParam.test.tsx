import { describe, it, expect, beforeEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, useLocation, useNavigationType } from 'react-router-dom';
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
});
