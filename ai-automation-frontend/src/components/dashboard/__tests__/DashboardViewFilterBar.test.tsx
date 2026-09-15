import { describe, it, expect, beforeAll, afterAll, afterEach, beforeEach } from 'vitest';
import { fireEvent, screen } from '@testing-library/react';
import { server } from '../../../test/server';
import { renderWithProviders } from '../../../test/utils';
import { useI18nStore } from '../../../i18n';
import { useDashboardLayoutStore } from '../../../stores/dashboardLayoutStore';
import { useDashboardSyncStore } from '../../../stores/dashboardSyncStore';
import { createDefaultLayout } from '../dashboardLayout';
import { DashboardViewFilterBar } from '../DashboardViewFilterBar';

const activeFilter = () => useDashboardLayoutStore.getState().views[0]?.filter;

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useDashboardLayoutStore.setState(createDefaultLayout());
  useDashboardSyncStore.setState({ baseVersionId: 'v1', dirty: false });
});

describe('DashboardViewFilterBar', () => {
  it('writes project and time range into the filter of the active view', async () => {
    renderWithProviders(<DashboardViewFilterBar editMode disabled={false} />);
    expect(await screen.findByRole('option', { name: 'Test Project' })).toBeInTheDocument();

    fireEvent.change(screen.getByRole('combobox', { name: 'Project' }), { target: { value: 'p1' } });
    expect(activeFilter()).toEqual({ projectId: 'p1' });
    expect(useDashboardSyncStore.getState().dirty).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: '7d' }));
    expect(activeFilter()).toEqual({ projectId: 'p1', days: 7 });
    expect(screen.getByRole('button', { name: '7d' })).toHaveAttribute('aria-pressed', 'true');

    // The default range and "all projects" are no filter at all.
    fireEvent.click(screen.getByRole('button', { name: '30d' }));
    fireEvent.change(screen.getByRole('combobox', { name: 'Project' }), { target: { value: '' } });
    expect(activeFilter()).toBeUndefined();
  });

  it('says which widgets the filter does not reach', () => {
    renderWithProviders(<DashboardViewFilterBar editMode disabled={false} />);
    expect(screen.getByText(/Todos, schedules, projects and the overall numbers/)).toBeInTheDocument();
  });

  it('only shows an active filter outside edit mode, and nothing without one', async () => {
    const { unmount } = renderWithProviders(<DashboardViewFilterBar editMode={false} disabled={false} />);
    expect(screen.queryByTestId('dashboard-view-filter')).not.toBeInTheDocument();
    unmount();

    useDashboardLayoutStore.setState({ views: [{ id: 'default', name: '', widgets: [], filter: { projectId: 'p1', days: 7 } }] });
    renderWithProviders(<DashboardViewFilterBar editMode={false} disabled={false} />);

    expect(await screen.findByText('Filtered: Test Project · Last 7 days')).toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
  });

  it('disables the controls while locked', () => {
    renderWithProviders(<DashboardViewFilterBar editMode disabled />);

    expect(screen.getByRole('combobox', { name: 'Project' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '7d' })).toBeDisabled();
  });
});
