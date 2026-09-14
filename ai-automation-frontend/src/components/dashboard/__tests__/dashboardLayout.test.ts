import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  LAYOUT_LIMITS,
  addWidgetInstance,
  createDefaultLayout,
  createInstanceId,
  migrateLayout,
  moveWidget,
  normalizeWidgets,
  removeWidgetInstance,
  sanitizeLayout,
  setWidgetHidden,
  updateWidgetConfig,
  type WidgetInstance,
  type WidgetTypeInfo,
} from '../dashboardLayout';
import { useDashboardLayoutStore } from '../../../stores/dashboardLayoutStore';

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
    const added = addWidgetInstance([single('a')], 'note', { text: 'hello' }, 'note-abc');
    expect(added).toEqual([single('a'), { instanceId: 'note-abc', type: 'note', config: { text: 'hello' } }]);
    const updated = updateWidgetConfig(added, 'note-abc', { text: 'bye', pinned: true });
    expect(updated[1]).toEqual({ instanceId: 'note-abc', type: 'note', config: { text: 'bye', pinned: true } });
    expect(removeWidgetInstance(updated, 'note-abc')).toEqual([single('a')]);
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
});
