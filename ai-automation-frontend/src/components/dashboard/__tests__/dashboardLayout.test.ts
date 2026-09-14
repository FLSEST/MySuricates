import { describe, it, expect, beforeEach } from 'vitest';
import {
  createDefaultLayout,
  moveWidget,
  normalizePlacements,
  sanitizeLayout,
  setWidgetHidden,
} from '../dashboardLayout';
import { useDashboardLayoutStore } from '../../../stores/dashboardLayoutStore';

const IDS = ['a', 'b', 'c', 'd'];

describe('normalizePlacements', () => {
  it('returns every known widget in registry order for an empty (default) list', () => {
    expect(normalizePlacements([], IDS)).toEqual([{ id: 'a' }, { id: 'b' }, { id: 'c' }, { id: 'd' }]);
  });

  it('keeps the stored order, drops unknown and duplicate ids and appends new widgets', () => {
    const stored = [{ id: 'c', hidden: true }, { id: 'removed' }, { id: 'a' }, { id: 'c' }];
    expect(normalizePlacements(stored, IDS)).toEqual([
      { id: 'c', hidden: true }, { id: 'a' }, { id: 'b' }, { id: 'd' },
    ]);
  });
});

describe('moveWidget', () => {
  const placements = [{ id: 'a' }, { id: 'b', hidden: true }, { id: 'c' }, { id: 'd' }];

  it('moves a widget onto the position of another one, across hidden widgets', () => {
    expect(moveWidget(placements, 'a', 'c').map((p) => p.id)).toEqual(['b', 'c', 'a', 'd']);
    expect(moveWidget(placements, 'd', 'a').map((p) => p.id)).toEqual(['d', 'a', 'b', 'c']);
  });

  it('keeps flags of the moved widget', () => {
    expect(moveWidget(placements, 'b', 'd')[3]).toEqual({ id: 'b', hidden: true });
  });

  it('returns the list unchanged for unknown ids or a drop onto itself', () => {
    expect(moveWidget(placements, 'x', 'a')).toBe(placements);
    expect(moveWidget(placements, 'a', 'x')).toBe(placements);
    expect(moveWidget(placements, 'a', 'a')).toBe(placements);
  });
});

describe('setWidgetHidden', () => {
  it('sets and clears the hidden flag without touching other widgets', () => {
    const hidden = setWidgetHidden([{ id: 'a' }, { id: 'b', size: 'full' }], 'b', true);
    expect(hidden).toEqual([{ id: 'a' }, { id: 'b', size: 'full', hidden: true }]);
    expect(setWidgetHidden(hidden, 'b', false)).toEqual([{ id: 'a' }, { id: 'b', size: 'full' }]);
  });
});

describe('sanitizeLayout', () => {
  it.each([null, undefined, 'garbage', 42, {}, { views: 'nope' }, { views: [] }, { views: [{ name: 'no id' }] }])(
    'falls back to the default layout for %j',
    (value) => {
      expect(sanitizeLayout(value)).toEqual(createDefaultLayout());
    },
  );

  it('filters invalid placements and sizes and repairs the active view id', () => {
    const layout = sanitizeLayout({
      activeViewId: 'missing',
      views: [{
        id: 'ops',
        name: 'Ops',
        widgets: [{ id: 'a', hidden: true, size: 'huge' }, { hidden: true }, 'b', { id: 'c', size: 'full', hidden: 'yes' }],
      }],
    });
    expect(layout).toEqual({
      activeViewId: 'ops',
      views: [{ id: 'ops', name: 'Ops', widgets: [{ id: 'a', hidden: true }, { id: 'c', size: 'full' }] }],
    });
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
      views: [{ id: 'default', name: '', widgets: [{ id: 'a' }] }, { id: 'ops', name: 'Ops', widgets: [] }],
    });

    useDashboardLayoutStore.getState().setActiveViewWidgets([{ id: 'b', hidden: true }]);
    expect(useDashboardLayoutStore.getState().views).toEqual([
      { id: 'default', name: '', widgets: [{ id: 'a' }] },
      { id: 'ops', name: 'Ops', widgets: [{ id: 'b', hidden: true }] },
    ]);

    useDashboardLayoutStore.getState().resetActiveView();
    expect(useDashboardLayoutStore.getState().views[1]?.widgets).toEqual([]);
    expect(useDashboardLayoutStore.getState().views[0]?.widgets).toEqual([{ id: 'a' }]);
  });

  it('persists the layout without the actions', () => {
    useDashboardLayoutStore.getState().setActiveViewWidgets([{ id: 'a', hidden: true }]);
    const stored = JSON.parse(localStorage.getItem('dashboard-layout') ?? '{}');
    expect(stored).toEqual({
      version: 1,
      state: { activeViewId: 'default', views: [{ id: 'default', name: '', widgets: [{ id: 'a', hidden: true }] }] },
    });
  });

  it('rehydrates a corrupt stored layout as the default', async () => {
    localStorage.setItem('dashboard-layout', JSON.stringify({ version: 1, state: { views: 'garbage' } }));
    await useDashboardLayoutStore.persist.rehydrate();
    const { activeViewId, views } = useDashboardLayoutStore.getState();
    expect({ activeViewId, views }).toEqual(createDefaultLayout());
  });
});
