import { describe, it, expect, beforeEach } from 'vitest';
import {
  LAYOUT_LIMITS,
  createDefaultLayout,
  type DashboardLayout,
  type DashboardView,
  type WidgetInstance,
} from '../../components/dashboard/dashboardLayout';
import { selectActiveView, useDashboardLayoutStore } from '../dashboardLayoutStore';
import { useDashboardSyncStore } from '../dashboardSyncStore';

const single = (id: string, extra: Partial<WidgetInstance> = {}): WidgetInstance => ({ instanceId: id, type: id, ...extra });

describe('dashboardLayoutStore', () => {
  beforeEach(() => {
    localStorage.clear();
    useDashboardLayoutStore.setState(createDefaultLayout());
    useDashboardSyncStore.setState({ baseVersionId: null, dirty: false });
  });

  it('updates and resets only the active view', () => {
    useDashboardLayoutStore.setState({
      activeViewId: 'ops',
      views: [{ id: 'default', name: '', widgets: [single('a')] }, { id: 'ops', name: 'Ops', widgets: [] }],
    });

    useDashboardLayoutStore.getState().setActiveViewWidgets([single('b', { hidden: true })]);
    expect(useDashboardLayoutStore.getState().views).toEqual([
      { id: 'default', name: '', widgets: [single('a')] },
      { id: 'ops', name: 'Ops', widgets: [single('b', { hidden: true })] },
    ]);

    useDashboardLayoutStore.getState().resetActiveView();
    expect(useDashboardLayoutStore.getState().views[1]?.widgets).toEqual([]);
    expect(useDashboardLayoutStore.getState().views[0]?.widgets).toEqual([single('a')]);
  });

  it('persists version 2 without the actions', () => {
    useDashboardLayoutStore.getState().setActiveViewWidgets([single('a', { hidden: true })]);
    expect(JSON.parse(localStorage.getItem('dashboard-layout') ?? '{}')).toEqual({
      version: 2,
      state: { activeViewId: 'default', views: [{ id: 'default', name: '', widgets: [single('a', { hidden: true })] }] },
    });
  });

  it('migrates a stored v1 layout on rehydrate', async () => {
    localStorage.setItem('dashboard-layout', JSON.stringify({
      version: 1,
      state: { activeViewId: 'default', views: [{ id: 'default', name: '', widgets: [{ id: 'quick-actions' }] }] },
    }));
    await useDashboardLayoutStore.persist.rehydrate();
    expect(useDashboardLayoutStore.getState().views[0]?.widgets).toEqual([single('quick-actions')]);
  });

  it('rehydrates a corrupt stored layout as the default', async () => {
    localStorage.setItem('dashboard-layout', JSON.stringify({ version: 2, state: { views: 'garbage' } }));
    await useDashboardLayoutStore.persist.rehydrate();
    const { activeViewId, views } = useDashboardLayoutStore.getState();
    expect({ activeViewId, views }).toEqual(createDefaultLayout());
  });

  it('leaves a layout from a newer app version untouched instead of overwriting it', async () => {
    const newer = JSON.stringify({
      version: 3,
      state: { activeViewId: 'default', views: [{ id: 'default', name: '', widgets: [], futureField: true }] },
    });
    localStorage.setItem('dashboard-layout', newer);

    await useDashboardLayoutStore.persist.rehydrate();

    const { activeViewId, views } = useDashboardLayoutStore.getState();
    expect({ activeViewId, views }).toEqual(createDefaultLayout());
    expect(localStorage.getItem('dashboard-layout')).toBe(newer);
  });

  it('marks local edits as unsynced, but not layouts loaded from the server', () => {
    useDashboardLayoutStore.getState().applyRemoteLayout({
      activeViewId: 'default',
      views: [{ id: 'default', name: '', widgets: [single('a')] }],
    });
    expect(useDashboardSyncStore.getState().dirty).toBe(false);
    expect(useDashboardLayoutStore.getState().views[0]?.widgets).toEqual([single('a')]);

    useDashboardLayoutStore.getState().setActiveViewWidgets([single('b')]);
    expect(useDashboardSyncStore.getState().dirty).toBe(true);

    useDashboardSyncStore.setState({ dirty: false });
    useDashboardLayoutStore.getState().resetActiveView();
    expect(useDashboardSyncStore.getState().dirty).toBe(true);
  });

  it('sanitizes a server layout before applying it', () => {
    useDashboardLayoutStore.getState().applyRemoteLayout({ activeViewId: 'x', views: 'garbage' } as unknown as DashboardLayout);
    const { activeViewId, views } = useDashboardLayoutStore.getState();
    expect({ activeViewId, views }).toEqual(createDefaultLayout());
  });

  it('replaces a view with the same id or appends it, within the view limit', () => {
    const ops: DashboardView = { id: 'ops', name: 'Ops', widgets: [single('a')] };
    expect(useDashboardLayoutStore.getState().upsertView(ops)).toBe(true);
    expect(useDashboardLayoutStore.getState().views.map((v) => v.id)).toEqual(['default', 'ops']);
    expect(useDashboardSyncStore.getState().dirty).toBe(true);

    useDashboardLayoutStore.getState().upsertView({ ...ops, name: 'Betrieb' });
    expect(useDashboardLayoutStore.getState().views[1]?.name).toBe('Betrieb');

    useDashboardLayoutStore.setState({
      views: Array.from({ length: LAYOUT_LIMITS.views }, (_, i) => ({ id: `v${i}`, name: '', widgets: [] })),
    });
    expect(useDashboardLayoutStore.getState().upsertView({ id: 'one-too-many', name: '', widgets: [] })).toBe(false);
    expect(useDashboardLayoutStore.getState().views).toHaveLength(LAYOUT_LIMITS.views);
  });

  it('switches the active view without marking a local change', () => {
    useDashboardLayoutStore.setState({ views: [{ id: 'default', name: '', widgets: [] }, { id: 'ops', name: 'Ops', widgets: [] }] });

    useDashboardLayoutStore.getState().setActiveView('ops');
    expect(useDashboardLayoutStore.getState().activeViewId).toBe('ops');
    useDashboardLayoutStore.getState().setActiveView('missing');
    expect(useDashboardLayoutStore.getState().activeViewId).toBe('ops');

    expect(useDashboardSyncStore.getState().dirty).toBe(false);
  });

  it('marks view edits as local changes, but only when they change something', () => {
    const store = () => useDashboardLayoutStore.getState();
    const dirty = () => useDashboardSyncStore.getState().dirty;
    const clean = () => useDashboardSyncStore.setState({ dirty: false });

    const added = store().addView() ?? '';
    expect(added).toMatch(/^view-[0-9a-f]{8}$/);
    expect(store().activeViewId).toBe(added);
    expect(dirty()).toBe(true);

    clean();
    store().renameView(added, '  Kosten ');
    expect(store().views[1]?.name).toBe('Kosten');
    expect(dirty()).toBe(true);

    clean();
    store().renameView(added, 'Kosten');
    expect(dirty()).toBe(false);

    const copy = store().duplicateView(added, 'Kosten (Kopie)') ?? '';
    expect(store().views.map((v) => v.id)).toEqual(['default', added, copy]);
    expect(store().activeViewId).toBe(copy);
    expect(dirty()).toBe(true);

    clean();
    store().setViewFilter('default', { days: 7 });
    expect(store().views[0]?.filter).toEqual({ days: 7 });
    expect(dirty()).toBe(true);

    clean();
    store().moveView(copy, 'default');
    expect(store().views.map((v) => v.id)).toEqual([copy, 'default', added]);
    expect(dirty()).toBe(true);

    clean();
    expect(store().removeView(copy)).toBe(true);
    expect(store().activeViewId).toBe('default');
    expect(dirty()).toBe(true);
  });

  it('refuses to remove the last view and to add views past the limit', () => {
    expect(useDashboardLayoutStore.getState().removeView('default')).toBe(false);

    useDashboardLayoutStore.setState({
      activeViewId: 'v0',
      views: Array.from({ length: LAYOUT_LIMITS.views }, (_, i) => ({ id: `v${i}`, name: '', widgets: [] })),
    });
    expect(useDashboardLayoutStore.getState().addView()).toBeNull();
    expect(useDashboardLayoutStore.getState().duplicateView('v0', 'Copy')).toBeNull();

    expect(useDashboardSyncStore.getState().dirty).toBe(false);
  });

  it('keeps the local active view when a server layout has it, otherwise shows its first view', () => {
    useDashboardLayoutStore.setState({
      activeViewId: 'ops',
      views: [{ id: 'default', name: '', widgets: [] }, { id: 'ops', name: 'Ops', widgets: [] }],
    });

    useDashboardLayoutStore.getState().applyRemoteLayout({
      activeViewId: 'default',
      views: [{ id: 'default', name: '', widgets: [single('a')] }, { id: 'ops', name: 'Betrieb', widgets: [] }],
    });
    expect(useDashboardLayoutStore.getState().activeViewId).toBe('ops');
    expect(useDashboardLayoutStore.getState().views[1]?.name).toBe('Betrieb');

    useDashboardLayoutStore.getState().applyRemoteLayout({
      activeViewId: 'plan',
      views: [{ id: 'start', name: '', widgets: [] }, { id: 'plan', name: 'Plan', widgets: [] }],
    });
    expect(useDashboardLayoutStore.getState().activeViewId).toBe('start');
    expect(useDashboardSyncStore.getState().dirty).toBe(false);
  });

  it('adds a view with template widgets as one local change and selects the shown view', () => {
    const widgets = [single('b'), single('a', { hidden: true })];

    const id = useDashboardLayoutStore.getState().addView('Betrieb', widgets) ?? '';

    expect(selectActiveView(useDashboardLayoutStore.getState())).toEqual({ id, name: 'Betrieb', widgets });
    expect(useDashboardSyncStore.getState().dirty).toBe(true);
    useDashboardLayoutStore.setState({ activeViewId: 'gone' });
    expect(selectActiveView(useDashboardLayoutStore.getState())?.id).toBe('default');
  });
});
