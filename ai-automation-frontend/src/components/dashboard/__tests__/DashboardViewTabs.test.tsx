import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { useI18nStore } from '../../../i18n';
import { useDashboardLayoutStore } from '../../../stores/dashboardLayoutStore';
import { useDashboardSyncStore } from '../../../stores/dashboardSyncStore';
import { LAYOUT_LIMITS, createDefaultLayout, type DashboardView } from '../dashboardLayout';
import { DashboardViewTabs } from '../DashboardViewTabs';

const view = (id: string, name = ''): DashboardView => ({ id, name, widgets: [] });
const layout = () => useDashboardLayoutStore.getState();
const tabNames = () => screen.getAllByRole('tab').map((tab) => tab.textContent);
// In edit mode the views are a list; each entry has a drag handle and a select button with the view id.
const editNames = () => Array.from(document.querySelectorAll('[role="listitem"] [data-view-id]')).map((el) => el.textContent);
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
    expect(editNames()).toEqual(['Overview', 'Overview (copy)']);
    expect(screen.getByRole('button', { name: 'Overview (copy)' })).toHaveAttribute('aria-current', 'true');
    expect(useDashboardSyncStore.getState().dirty).toBe(true);

    fireEvent.click(screen.getByRole('button', { name: 'Add view' }));
    expect(editNames()).toEqual(['Overview', 'Overview (copy)']);
    expect(layout().activeViewId).toBe(layout().views[2]?.id);
    const field = nameField();
    expect(field).toHaveFocus();
    expect(field).toHaveAttribute('placeholder', 'Overview');

    fireEvent.change(field, { target: { value: 'Third' } });
    fireEvent.keyDown(field, { key: 'Enter' });
    expect(editNames()).toEqual(['Overview', 'Overview (copy)', 'Third']);
    expect(layout().views[0]?.name).toBe('');
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
    fireEvent.click(screen.getByRole('button', { name: 'Ops' }));
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

  it('is a tab list outside edit mode: tabs control the widget panel, one tab stop, arrow keys switch', () => {
    useDashboardLayoutStore.setState({ activeViewId: 'ops', views: [view('default'), view('ops', 'Ops'), view('plan', 'Plan')] });
    render(<DashboardViewTabs editMode={false} disabled={false} />);

    const ops = screen.getByRole('tab', { name: 'Ops' });
    expect(ops).toHaveAttribute('aria-controls', 'dashboard-view-panel');
    expect(ops).toHaveAttribute('id', 'dashboard-view-tab-ops');
    expect(screen.getAllByRole('tab').map((tab) => tab.tabIndex)).toEqual([-1, 0, -1]);

    fireEvent.keyDown(ops, { key: 'ArrowRight' });
    expect(layout().activeViewId).toBe('plan');
    expect(screen.getByRole('tab', { name: 'Plan' })).toHaveFocus();

    fireEvent.keyDown(screen.getByRole('tab', { name: 'Plan' }), { key: 'ArrowRight' });
    expect(layout().activeViewId).toBe('default');
    fireEvent.keyDown(screen.getByRole('tab', { name: 'Overview' }), { key: 'ArrowLeft' });
    expect(layout().activeViewId).toBe('plan');
    fireEvent.keyDown(screen.getByRole('tab', { name: 'Plan' }), { key: 'Home' });
    expect(layout().activeViewId).toBe('default');
    fireEvent.keyDown(screen.getByRole('tab', { name: 'Overview' }), { key: 'End' });
    expect(layout().activeViewId).toBe('plan');
    expect(useDashboardSyncStore.getState().dirty).toBe(false);
  });

  it('is no tab list in edit mode, where every view carries a drag handle', () => {
    useDashboardLayoutStore.setState({ activeViewId: 'ops', views: [view('default'), view('ops', 'Ops')] });
    render(<DashboardViewTabs editMode disabled={false} />);

    expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
    expect(screen.queryByRole('tab')).not.toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Dashboard views' })).toBeInTheDocument();
    expect(screen.getAllByRole('listitem')).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'Ops' })).toHaveAttribute('aria-current', 'true');
    expect(screen.getByRole('button', { name: 'Overview' })).not.toHaveAttribute('aria-current');
  });

  it.each([false, true])('scrolls the shown view into the visible part of the strip (editMode=%s)', (editMode) => {
    const scrolled: (string | null)[] = [];
    const original = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = function scrollIntoView(this: Element) {
      scrolled.push(this.getAttribute('data-view-id'));
    };
    try {
      const views = Array.from({ length: 8 }, (_, i) => view(`v${i}`, `View ${i}`));
      useDashboardLayoutStore.setState({ activeViewId: 'v7', views });
      render(<DashboardViewTabs editMode={editMode} disabled={false} />);
      expect(scrolled).toEqual(['v7']);

      act(() => layout().setActiveView('v2'));
      expect(scrolled).toEqual(['v7', 'v2']);
    } finally {
      Element.prototype.scrollIntoView = original;
    }
  });

  it('adds a view from a template: named after it, shown, with the template’s widgets', () => {
    render(<DashboardViewTabs editMode disabled={false} />);

    fireEvent.click(screen.getByRole('button', { name: 'Add view from template' }));
    expect(screen.getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['Operations', 'Cost & models', 'Quality', 'Planning']);
    fireEvent.click(screen.getByRole('menuitem', { name: 'Planning' }));

    const created = layout().views[1];
    expect(created?.name).toBe('Planning');
    expect(layout().activeViewId).toBe(created?.id);
    expect(created?.widgets.filter((w) => !w.hidden).map((w) => w.instanceId)).toEqual(['stat-planned-today', 'stat-planned-week', 'upcoming-schedules']);
    expect(created?.widgets.some((w) => w.instanceId === 'recent-runs' && w.hidden)).toBe(true);
    expect(editNames()).toEqual(['Overview', 'Planning']);
    expect(useDashboardSyncStore.getState().dirty).toBe(true);
  });

  it('offers no template while locked or at the view limit', () => {
    const { unmount } = render(<DashboardViewTabs editMode disabled />);
    expect(screen.getByRole('button', { name: 'Add view from template' })).toBeDisabled();
    unmount();

    useDashboardLayoutStore.setState({
      activeViewId: 'v0',
      views: Array.from({ length: LAYOUT_LIMITS.views }, (_, i) => view(`v${i}`, `View ${i}`)),
    });
    render(<DashboardViewTabs editMode disabled={false} />);
    expect(screen.getByRole('button', { name: 'Add view from template' })).toBeDisabled();
  });
});
