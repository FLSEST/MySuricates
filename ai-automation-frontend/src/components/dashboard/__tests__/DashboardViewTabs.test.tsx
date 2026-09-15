import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';
import { useDashboardLayoutStore } from '../../../stores/dashboardLayoutStore';
import { useDashboardSyncStore } from '../../../stores/dashboardSyncStore';
import { LAYOUT_LIMITS, createDefaultLayout, type DashboardView } from '../dashboardLayout';
import { DashboardViewTabs } from '../DashboardViewTabs';

const view = (id: string, name = ''): DashboardView => ({ id, name, widgets: [] });
const layout = () => useDashboardLayoutStore.getState();
const tabNames = () => screen.getAllByRole('tab').map((tab) => tab.textContent);
const nameField = () => screen.getByRole('textbox', { name: 'View name' }) as HTMLInputElement;

beforeEach(() => {
  localStorage.clear();
  useI18nStore.setState({ locale: 'en' });
  useDashboardLayoutStore.setState(createDefaultLayout());
  useDashboardSyncStore.setState({ baseVersionId: 'v1', dirty: false });
});
afterEach(() => vi.restoreAllMocks());

describe('DashboardViewTabs', () => {
  it('shows no tab strip for a single view outside edit mode', () => {
    render(<DashboardViewTabs editMode={false} disabled={false} />);
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
  });

  it('switches views by tab without marking a local change', () => {
    useDashboardLayoutStore.setState({ activeViewId: 'default', views: [view('default'), view('ops', 'Ops')] });
    render(<DashboardViewTabs editMode={false} disabled={false} />);

    expect(tabNames()).toEqual(['Overview', 'Ops']);
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true');
    fireEvent.click(screen.getByRole('tab', { name: 'Ops' }));

    expect(layout().activeViewId).toBe('ops');
    expect(screen.getByRole('tab', { name: 'Ops' })).toHaveAttribute('aria-selected', 'true');
    expect(useDashboardSyncStore.getState().dirty).toBe(false);
  });

  it('renders view names as plain text', () => {
    const markup = '<img src=x onerror=alert(1)>';
    useDashboardLayoutStore.setState({ activeViewId: 'default', views: [view('default'), view('ops', markup)] });
    render(<DashboardViewTabs editMode={false} disabled={false} />);

    expect(screen.getByRole('tab', { name: markup })).toBeInTheDocument();
    expect(document.querySelector('img')).toBeNull();
  });

  it('duplicates and adds views in edit mode and shows the new one', () => {
    render(<DashboardViewTabs editMode disabled={false} />);

    fireEvent.click(screen.getByRole('button', { name: 'Duplicate view' }));
    expect(tabNames()).toEqual(['Overview', 'Overview (copy)']);
    expect(screen.getByRole('tab', { name: 'Overview (copy)' })).toHaveAttribute('aria-selected', 'true');
    expect(useDashboardSyncStore.getState().dirty).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: 'Add view' }));
    expect(tabNames()).toEqual(['Overview', 'Overview (copy)', 'Overview']);
    expect(layout().activeViewId).toBe(layout().views[2]?.id);
  });

  it('renames the active view inline, trimmed and cut by code points', () => {
    useDashboardLayoutStore.setState({ activeViewId: 'ops', views: [view('default'), view('ops', 'Ops')] });
    render(<DashboardViewTabs editMode disabled={false} />);

    fireEvent.click(screen.getByRole('button', { name: 'Rename view' }));
    expect(nameField()).toHaveValue('Ops');
    fireEvent.change(nameField(), { target: { value: '  Betrieb  ' } });
    fireEvent.keyDown(nameField(), { key: 'Enter' });
    expect(layout().views[1]?.name).toBe('Betrieb');
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Rename view' }));
    fireEvent.change(nameField(), { target: { value: '😀'.repeat(LAYOUT_LIMITS.nameLength + 10) } });
    expect(Array.from(nameField().value)).toHaveLength(LAYOUT_LIMITS.nameLength);
    fireEvent.blur(nameField());
    expect(layout().views[1]?.name).toBe('😀'.repeat(LAYOUT_LIMITS.nameLength));
  });

  it('cancels a rename with Escape', () => {
    useDashboardLayoutStore.setState({ activeViewId: 'ops', views: [view('default'), view('ops', 'Ops')] });
    render(<DashboardViewTabs editMode disabled={false} />);

    fireEvent.click(screen.getByRole('button', { name: 'Rename view' }));
    fireEvent.change(nameField(), { target: { value: 'Other' } });
    fireEvent.keyDown(nameField(), { key: 'Escape' });

    expect(layout().views[1]?.name).toBe('Ops');
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(useDashboardSyncStore.getState().dirty).toBe(false);
  });

  it('deletes the active view only after confirmation and never the last view', () => {
    useDashboardLayoutStore.setState({ activeViewId: 'ops', views: [view('default'), view('ops', 'Ops')] });
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);
    render(<DashboardViewTabs editMode disabled={false} />);

    fireEvent.click(screen.getByRole('button', { name: 'Delete view' }));
    expect(confirm).toHaveBeenCalledWith('Delete the view “Ops”? Its widgets and filter are removed.');
    expect(layout().views).toHaveLength(2);

    fireEvent.click(screen.getByRole('button', { name: 'Delete view' }));
    expect(layout().views.map((v) => v.id)).toEqual(['default']);
    expect(layout().activeViewId).toBe('default');
    expect(screen.getByRole('button', { name: 'Delete view' })).toBeDisabled();
  });

  it('locks every view edit while disabled but still switches views', () => {
    useDashboardLayoutStore.setState({ activeViewId: 'default', views: [view('default'), view('ops', 'Ops')] });
    render(<DashboardViewTabs editMode disabled />);

    for (const name of ['Add view', 'Rename view', 'Duplicate view', 'Delete view', 'Move view: Ops']) {
      expect(screen.getByRole('button', { name })).toBeDisabled();
    }
    fireEvent.click(screen.getByRole('tab', { name: 'Ops' }));
    expect(layout().activeViewId).toBe('ops');
  });

  it('offers no further views at the view limit', () => {
    useDashboardLayoutStore.setState({
      activeViewId: 'v0',
      views: Array.from({ length: LAYOUT_LIMITS.views }, (_, i) => view(`v${i}`, `View ${i}`)),
    });
    render(<DashboardViewTabs editMode disabled={false} />);

    expect(screen.getByRole('button', { name: 'Add view' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Duplicate view' })).toBeDisabled();
  });
});
