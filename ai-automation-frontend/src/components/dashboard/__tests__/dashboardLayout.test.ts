import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  LAYOUT_LIMITS,
  addLayoutView,
  addWidgetInstance,
  cleanViewName,
  createDefaultLayout,
  createInstanceId,
  createViewId,
  duplicateLayoutView,
  isCustomizedLayout,
  layoutContentKey,
  migrateLayout,
  moveLayoutView,
  moveWidget,
  normalizeWidgets,
  removeLayoutView,
  removeWidgetInstance,
  renameLayoutView,
  sanitizeLayout,
  selectLayoutView,
  setLayoutViewFilter,
  setWidgetHidden,
  truncateCodePoints,
  updateWidgetConfig,
  type DashboardLayout,
  type DashboardView,
  type WidgetInstance,
  type WidgetTypeInfo,
} from '../dashboardLayout';
import { useDashboardLayoutStore } from '../../../stores/dashboardLayoutStore';
import { useDashboardSyncStore } from '../../../stores/dashboardSyncStore';

const TYPES: WidgetTypeInfo[] = [{ id: 'a' }, { id: 'b' }, { id: 'note', multiple: true }, { id: 'c' }];
const single = (id: string, extra: Partial<WidgetInstance> = {}): WidgetInstance => ({ instanceId: id, type: id, ...extra });

describe('normalizeWidgets', () => {
  it('returns all singleton widgets in registry order for an empty (default) list, never multi widgets', () => {
    expect(normalizeWidgets([], TYPES)).toEqual([single('a'), single('b'), single('c')]);
  });

  it('keeps the stored order, drops unknown types and duplicates, appends missing singletons', () => {
    const stored: WidgetInstance[] = [
      single('c', { hidden: true }),
      { instanceId: 'gone', type: 'removed-widget' },
      { instanceId: 'note-1', type: 'note', config: { text: 'x' } },
      single('a'),
      single('c'),
      { instanceId: 'a-copy', type: 'a' },
      { instanceId: 'note-1', type: 'note' },
      { instanceId: 'note-2', type: 'note' },
    ];
    expect(normalizeWidgets(stored, TYPES)).toEqual([
      single('c', { hidden: true }),
      { instanceId: 'note-1', type: 'note', config: { text: 'x' } },
      single('a'),
      { instanceId: 'note-2', type: 'note' },
      single('b'),
    ]);
  });

  it('drops singletons stored under another id and multi instances that take a singleton id', () => {
    const stored: WidgetInstance[] = [
      { instanceId: 'b', type: 'a' },
      { instanceId: 'a', type: 'note' },
      { instanceId: 'note-1', type: 'note' },
    ];
    expect(normalizeWidgets(stored, TYPES)).toEqual([
      { instanceId: 'note-1', type: 'note' }, single('a'), single('b'), single('c'),
    ]);
  });
});

describe('moveWidget', () => {
  const widgets = [single('a'), single('b', { hidden: true }), single('c'), { instanceId: 'note-1', type: 'note' }];

  it('moves an instance onto the position of another one, across hidden widgets', () => {
    expect(moveWidget(widgets, 'a', 'c').map((w) => w.instanceId)).toEqual(['b', 'c', 'a', 'note-1']);
    expect(moveWidget(widgets, 'note-1', 'a').map((w) => w.instanceId)).toEqual(['note-1', 'a', 'b', 'c']);
  });

  it('returns the list unchanged for unknown ids or a drop onto itself', () => {
    expect(moveWidget(widgets, 'x', 'a')).toBe(widgets);
    expect(moveWidget(widgets, 'a', 'x')).toBe(widgets);
    expect(moveWidget(widgets, 'a', 'a')).toBe(widgets);
  });
});

describe('instance operations', () => {
  it('sets and clears the hidden flag of one instance only', () => {
    const hidden = setWidgetHidden([single('a'), single('b', { size: 'full' })], 'b', true);
    expect(hidden).toEqual([single('a'), single('b', { size: 'full', hidden: true })]);
    expect(setWidgetHidden(hidden, 'b', false)).toEqual([single('a'), single('b', { size: 'full' })]);
  });

  it('adds, reconfigures and removes multi instances', () => {
    const added = addWidgetInstance([single('a')], TYPES, 'note', { text: 'hello' }, 'note-abc');
    expect(added).toEqual([single('a'), { instanceId: 'note-abc', type: 'note', config: { text: 'hello' } }]);
    const updated = updateWidgetConfig(added, 'note-abc', { text: 'bye', pinned: true });
    expect(updated[1]).toEqual({ instanceId: 'note-abc', type: 'note', config: { text: 'bye', pinned: true } });
    expect(removeWidgetInstance(updated, 'note-abc')).toEqual([single('a')]);
  });

  it('refuses to add singletons, unknown types and ids that are taken', () => {
    const widgets: WidgetInstance[] = [single('a'), { instanceId: 'note-1', type: 'note' }];
    expect(addWidgetInstance(widgets, TYPES, 'a', {}, 'a-2')).toBe(widgets);
    expect(addWidgetInstance(widgets, TYPES, 'unknown', {}, 'unknown-1')).toBe(widgets);
    expect(addWidgetInstance(widgets, TYPES, 'note', {}, 'note-1')).toBe(widgets);
    expect(addWidgetInstance(widgets, TYPES, 'note', {}, 'b')).toBe(widgets);
    expect(addWidgetInstance(widgets, TYPES, 'note', {}, 'note-2')).toEqual([...widgets, { instanceId: 'note-2', type: 'note' }]);
  });

  it('removes an empty config instead of storing {}', () => {
    expect(updateWidgetConfig([{ instanceId: 'note-1', type: 'note', config: { text: 'x' } }], 'note-1', {}))
      .toEqual([{ instanceId: 'note-1', type: 'note' }]);
  });

  it('recognizes layouts that differ from the default', () => {
    expect(isCustomizedLayout(createDefaultLayout())).toBe(false);
    expect(isCustomizedLayout({ activeViewId: 'default', views: [{ id: 'default', name: '', widgets: [single('a')] }] })).toBe(true);
    expect(isCustomizedLayout({ activeViewId: 'default', views: [{ id: 'default', name: '', widgets: [], filter: { days: 7 } }] })).toBe(true);
    expect(isCustomizedLayout({
      activeViewId: 'default',
      views: [{ id: 'default', name: '', widgets: [] }, { id: 'ops', name: 'Ops', widgets: [] }],
    })).toBe(true);
  });

  it('creates instance ids that are valid, type-prefixed and unique', () => {
    const first = createInstanceId('workflow-status');
    const second = createInstanceId('workflow-status');
    expect(first).toMatch(/^workflow-status-[0-9a-f]{8}$/);
    expect(first).not.toBe(second);
  });

  it('creates instance ids in a non-secure context, where crypto.randomUUID is undefined', () => {
    vi.stubGlobal('crypto', { getRandomValues: globalThis.crypto.getRandomValues.bind(globalThis.crypto) });
    try {
      expect(createInstanceId('workflow-status')).toMatch(/^workflow-status-[0-9a-f]{8}$/);
    } finally {
      vi.unstubAllGlobals();
    }
  });
});

describe('view operations', () => {
  const view = (id: string, extra: Partial<DashboardView> = {}): DashboardView => ({ id, name: '', widgets: [], ...extra });
  const twoViews: DashboardLayout = {
    activeViewId: 'default',
    views: [view('default'), view('ops', { name: 'Ops', widgets: [single('a')], filter: { days: 7 } })],
  };

  it('creates view ids that follow the id rules', () => {
    const first = createViewId();
    expect(first).toMatch(/^view-[0-9a-f]{8}$/);
    expect(first).not.toBe(createViewId());
  });

  it('trims names and cuts them by code points, never inside an emoji', () => {
    const tooLong = '😀'.repeat(LAYOUT_LIMITS.nameLength + 5);
    expect(cleanViewName('  Betrieb  ')).toBe('Betrieb');
    expect(cleanViewName(tooLong)).toBe('😀'.repeat(LAYOUT_LIMITS.nameLength));
    expect(truncateCodePoints('a😀b', 2)).toBe('a😀');
    expect(sanitizeLayout({ activeViewId: 'x', views: [{ id: 'x', name: tooLong, widgets: [] }] }).views[0]?.name)
      .toBe('😀'.repeat(LAYOUT_LIMITS.nameLength));
  });

  it('adds an empty view, shows it and respects the view limit', () => {
    expect(addLayoutView(twoViews, '  Kosten ', 'view-00000001')).toEqual({
      activeViewId: 'view-00000001',
      views: [...twoViews.views, view('view-00000001', { name: 'Kosten' })],
    });
    expect(addLayoutView(twoViews, '', 'ops')).toBe(twoViews);
    const full: DashboardLayout = { activeViewId: 'v0', views: Array.from({ length: LAYOUT_LIMITS.views }, (_, i) => view(`v${i}`)) };
    expect(addLayoutView(full, '', 'view-00000002')).toBe(full);
  });

  it('renames a view and leaves unknown views and unchanged names alone', () => {
    expect(renameLayoutView(twoViews, 'ops', ' Betrieb ').views[1]?.name).toBe('Betrieb');
    expect(renameLayoutView(twoViews, 'ops', 'Ops')).toBe(twoViews);
    expect(renameLayoutView(twoViews, 'missing', 'x')).toBe(twoViews);
  });

  it('duplicates widgets and filter right after the original and shows the copy', () => {
    const copied = duplicateLayoutView(twoViews, 'ops', 'Ops (copy)', 'view-00000003');
    expect(copied.activeViewId).toBe('view-00000003');
    expect(copied.views[2]).toEqual({ id: 'view-00000003', name: 'Ops (copy)', widgets: [single('a')], filter: { days: 7 } });
    expect(copied.views[2]?.widgets).not.toBe(twoViews.views[1]?.widgets);
    expect(duplicateLayoutView(twoViews, 'default', 'Copy', 'view-00000004').views.map((v) => v.id))
      .toEqual(['default', 'view-00000004', 'ops']);
    expect(duplicateLayoutView(twoViews, 'missing', 'x', 'view-00000005')).toBe(twoViews);
  });

  it('removes a view, hands the active view to a neighbour and keeps the last view', () => {
    expect(removeLayoutView({ ...twoViews, activeViewId: 'ops' }, 'ops')).toEqual({ activeViewId: 'default', views: [view('default')] });
    const three: DashboardLayout = { activeViewId: 'b', views: [view('a'), view('b'), view('c')] };
    expect(removeLayoutView(three, 'b')).toEqual({ activeViewId: 'c', views: [view('a'), view('c')] });
    expect(removeLayoutView(three, 'a')).toEqual({ activeViewId: 'b', views: [view('b'), view('c')] });
    const last: DashboardLayout = { activeViewId: 'a', views: [view('a')] };
    expect(removeLayoutView(last, 'a')).toBe(last);
    expect(removeLayoutView(three, 'missing')).toBe(three);
  });

  it('reorders views and switches only to existing views', () => {
    expect(moveLayoutView(twoViews, 'ops', 'default').views.map((v) => v.id)).toEqual(['ops', 'default']);
    expect(moveLayoutView(twoViews, 'ops', 'ops')).toBe(twoViews);
    expect(selectLayoutView(twoViews, 'ops').activeViewId).toBe('ops');
    expect(selectLayoutView(twoViews, 'default')).toBe(twoViews);
    expect(selectLayoutView(twoViews, 'missing')).toBe(twoViews);
  });

  it('sets a cleaned filter and removes an empty one', () => {
    expect(setLayoutViewFilter(twoViews, 'default', { projectId: 'p1', days: 400 }).views[0])
      .toEqual(view('default', { filter: { projectId: 'p1' } }));
    expect(setLayoutViewFilter(twoViews, 'ops', { days: 7 })).toBe(twoViews);
    expect(setLayoutViewFilter(twoViews, 'ops', {}).views[1]).toEqual(view('ops', { name: 'Ops', widgets: [single('a')] }));
    expect(setLayoutViewFilter(twoViews, 'missing', { days: 7 })).toBe(twoViews);
  });

  it('compares layout content without the active view', () => {
    expect(layoutContentKey({ ...twoViews, activeViewId: 'ops' })).toBe(layoutContentKey(twoViews));
    expect(layoutContentKey(renameLayoutView(twoViews, 'ops', 'Betrieb'))).not.toBe(layoutContentKey(twoViews));
  });
});

describe('sanitizeLayout', () => {
  it.each([null, undefined, 'garbage', 42, {}, { views: 'nope' }, { views: [] }, { views: [{ name: 'no id' }] }])(
    'falls back to the default layout for %j',
    (value) => {
      expect(sanitizeLayout(value)).toEqual(createDefaultLayout());
    },
  );

  it('keeps valid instances, config primitives and filters; drops everything else', () => {
    const layout = sanitizeLayout({
      activeViewId: 'missing',
      views: [
        {
          id: 'ops',
          name: 'Ops',
          filter: { projectId: '00000000-0000-0000-0000-000000000000', days: 7, extra: 'x' },
          widgets: [
            { instanceId: 'a', type: 'a', hidden: true, size: 'huge' },
            { instanceId: 'note-1', type: 'note', config: { text: 'hi', count: 3, on: false, nested: { x: 1 }, list: [1], bad: Infinity } },
            { instanceId: 'Bad Id', type: 'a' },
            { type: 'a' },
            'b',
            { instanceId: 'c', type: 'c', hidden: 'yes', config: { '<script>': 'x', long: 'x'.repeat(LAYOUT_LIMITS.configString + 1) } },
          ],
        },
        { id: 'ops', name: 'duplicate id', widgets: [] },
        { id: 'plan', name: 'P'.repeat(200), filter: { days: 0 }, widgets: [] },
      ],
    });
    expect(layout).toEqual({
      activeViewId: 'ops',
      views: [
        {
          id: 'ops',
          name: 'Ops',
          filter: { projectId: '00000000-0000-0000-0000-000000000000', days: 7 },
          widgets: [
            { instanceId: 'a', type: 'a', hidden: true },
            { instanceId: 'note-1', type: 'note', config: { text: 'hi', count: 3, on: false } },
            { instanceId: 'c', type: 'c' },
          ],
        },
        { id: 'plan', name: 'P'.repeat(LAYOUT_LIMITS.nameLength), widgets: [] },
      ],
    });
  });

  it('keeps only the first widget per instance id within a view', () => {
    const layout = sanitizeLayout({
      activeViewId: 'default',
      views: [{
        id: 'default',
        name: '',
        widgets: [
          { instanceId: 'a', type: 'a', hidden: true },
          { instanceId: 'a', type: 'a' },
          { instanceId: 'note-1', type: 'note' },
          { instanceId: 'note-1', type: 'note', config: { text: 'x' } },
        ],
      }],
    });
    expect(layout.views[0]?.widgets).toEqual([
      { instanceId: 'a', type: 'a', hidden: true },
      { instanceId: 'note-1', type: 'note' },
    ]);
  });
});

describe('migrateLayout', () => {
  const v1 = {
    activeViewId: 'default',
    views: [{ id: 'default', name: '', widgets: [{ id: 'quick-actions' }, { id: 'stat-version', hidden: true, size: 'full' }] }],
  };

  it('turns v1 placements into v2 singleton instances', () => {
    expect(sanitizeLayout(migrateLayout(v1, 1))).toEqual({
      activeViewId: 'default',
      views: [{
        id: 'default',
        name: '',
        widgets: [
          { instanceId: 'quick-actions', type: 'quick-actions' },
          { instanceId: 'stat-version', type: 'stat-version', hidden: true, size: 'full' },
        ],
      }],
    });
  });

  it('leaves current-version data and non-objects untouched', () => {
    const v2 = { activeViewId: 'default', views: [] };
    expect(migrateLayout(v2, 2)).toBe(v2);
    expect(migrateLayout('garbage', 1)).toBe('garbage');
  });
});

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
});
